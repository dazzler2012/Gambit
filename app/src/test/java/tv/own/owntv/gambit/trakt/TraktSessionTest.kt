package tv.own.owntv.gambit.trakt

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraktSessionTest {
    private val playbackOk = Canned(body = "[]")

    @Test
    fun `a valid token is used without refreshing`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/sync/playback", playbackOk)

        assertEquals(emptyList<TraktPlaybackEntry>(), h.client.sync.playback().getOrNull())
        assertEquals("Bearer ${Fake.ACCESS}", h.host.seen.single().headers["Authorization"])
        assertEquals(0, h.store.writes)
    }

    @Test
    fun `a token about to expire is refreshed first and the new pair saved`() = runBlocking {
        val h = Harness(tokens = Harness.expiringTokens())
        h.host.on("POST", "/oauth/token", Canned(body = Fake.fixture("token_ok.json")))
        h.host.on("GET", "/sync/playback", playbackOk)

        h.client.sync.playback()

        val refresh = JSONObject(h.host.seen("POST", "/oauth/token").single().body)
        assertEquals(Fake.REFRESH, refresh.getString("refresh_token"))
        assertEquals("refresh_token", refresh.getString("grant_type"))
        assertEquals(Fake.CLIENT_ID, refresh.getString("client_id"))
        assertEquals(TraktConfig.DEVICE_REDIRECT_URI, refresh.getString("redirect_uri"))
        assertEquals("Bearer ${Fake.NEW_ACCESS}", h.host.seen("GET", "/sync/playback").single().headers["Authorization"])
        assertEquals(Fake.NEW_REFRESH, h.store.tokens!!.refreshToken)
    }

    @Test
    fun `a 401 refreshes once and retries once`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/sync/playback", Canned(status = 401), playbackOk)
        h.host.on("POST", "/oauth/token", Canned(body = Fake.fixture("token_ok.json")))

        assertEquals(emptyList<TraktPlaybackEntry>(), h.client.sync.playback().getOrNull())
        val calls = h.host.seen("GET", "/sync/playback")
        assertEquals(listOf("Bearer ${Fake.ACCESS}", "Bearer ${Fake.NEW_ACCESS}"), calls.map { it.headers["Authorization"] })
    }

    @Test
    fun `a second 401 after refreshing is unauthorized`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/sync/playback", Canned(status = 401))
        h.host.on("POST", "/oauth/token", Canned(body = Fake.fixture("token_ok.json")))

        assertEquals(TraktError.Unauthorized, h.client.sync.playback().errorOrNull())
        assertEquals(2, h.host.seen("GET", "/sync/playback").size)
        assertEquals(1, h.host.seen("POST", "/oauth/token").size)
    }

    @Test
    fun `a rejected refresh token clears the tokens and asks to sign in again`() = runBlocking {
        for (status in listOf(400, 401)) {
            val h = Harness(tokens = Harness.expiringTokens())
            h.host.on("POST", "/oauth/token", Canned(status = status, body = Fake.fixture("token_invalid_grant.json")))

            assertEquals(TraktError.SignInExpired, h.client.sync.playback().errorOrNull())
            assertNull(h.store.tokens)
            assertEquals(0, h.host.seen("GET", "/sync/playback").size)
        }
    }

    @Test
    fun `a refresh that fails in transit keeps the tokens`() = runBlocking {
        val h = Harness(tokens = Harness.expiringTokens())
        h.host.on("POST", "/oauth/token", Canned(status = 503))

        assertEquals(TraktError.Server(503), h.client.sync.playback().errorOrNull())
        assertEquals(Fake.REFRESH, h.store.tokens!!.refreshToken)
    }

    @Test
    fun `concurrent calls with an expiring token refresh only once`() = runBlocking {
        val h = Harness(tokens = Harness.expiringTokens())
        h.host.on("POST", "/oauth/token", Canned(body = Fake.fixture("token_ok.json")))
        h.host.on("GET", "/sync/playback", playbackOk)

        (1..5).map { async { h.client.sync.playback() } }.awaitAll()

        assertEquals(1, h.host.seen("POST", "/oauth/token").size)
        assertEquals(5, h.host.seen("GET", "/sync/playback").size)
    }

    @Test
    fun `not signed in makes no request`() = runBlocking {
        val h = Harness(tokens = null)

        assertEquals(TraktError.NotSignedIn, h.client.sync.playback().errorOrNull())
        assertEquals(TraktError.NotSignedIn, h.client.session.refresh().errorOrNull())
        assertEquals(0, h.host.seen.size)
    }

    @Test
    fun `forced refresh and sign out`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/oauth/token", Canned(body = Fake.fixture("token_ok.json")))

        assertEquals(Fake.NEW_ACCESS, h.client.session.refresh().getOrNull()!!.accessToken)
        assertEquals(true, h.client.session.isSignedIn().getOrNull())
        h.client.session.signOut()
        assertEquals(false, h.client.session.isSignedIn().getOrNull())
    }

    @Test
    fun `a failing token store is a typed error`() = runBlocking {
        val h = Harness()
        h.store.failWith = java.io.IOException("fake")

        assertEquals(TraktError.Storage("IOException"), h.client.sync.playback().errorOrNull())
        assertEquals(TraktError.Storage("IOException"), h.client.session.isSignedIn().errorOrNull())
    }
}
