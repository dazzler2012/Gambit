package tv.own.owntv.gambit.trakt

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live renewal check against the fake host (trakt.fake.test). Nothing leaves the test. */
class TraktRenewalCheckTest {
    /** A token reply with its own pair, so each renewal is told apart. */
    private fun tokenReply(n: Int) = Canned(
        body = JSONObject()
            .put("access_token", "fake-access-$n")
            .put("token_type", "bearer")
            .put("expires_in", 604_800)
            .put("refresh_token", "fake-refresh-$n")
            .put("scope", "public")
            .put("created_at", 1_700_000_000L + n)
            .toString(),
    )

    private val history = Canned(body = Fake.fixture("history.json"))

    private fun Harness.check() = TraktRenewalCheck(client, store) { clock.nowMillis }

    @Test
    fun `all three renewals work`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/oauth/token", tokenReply(1), tokenReply(2), tokenReply(3))
        h.host.on("GET", "/sync/history", history, Canned(status = 401), history)

        val r = h.check().run()

        assertEquals(TraktRenewalCheck.Step(true, null, 1_700_000_001L + 604_800L), r.forced)
        assertEquals(TraktRenewalCheck.Step(true, null, 1_700_000_002L + 604_800L), r.automatic)
        assertEquals(TraktRenewalCheck.Step(true, null, 1_700_000_003L + 604_800L), r.afterRejected)
        assertEquals("fake-refresh-3", h.store.tokens!!.refreshToken)
        // Step 2 renewed before its call; step 3 sent the wrong token, then retried with the new one.
        val auth = h.host.seen("GET", "/sync/history").map { it.headers["Authorization"] }
        assertEquals(listOf("Bearer fake-access-2", "Bearer ${TraktRenewalCheck.WRONG_ACCESS_TOKEN}", "Bearer fake-access-3"), auth)
        assertEquals("1", h.host.seen("GET", "/sync/history").first().query?.substringAfter("limit=")?.substringBefore("&"))
    }

    @Test
    fun `a rejected refresh token signs out and stops the check`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/oauth/token", Canned(status = 400))

        val r = h.check().run()

        assertEquals(TraktError.SignInExpired, r.forced.error)
        assertFalse(r.forced.renewed)
        assertNull(r.automatic)
        assertNull(r.afterRejected)
        assertNull(h.store.tokens)
    }

    @Test
    fun `no network keeps the sign in`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/oauth/token", Canned(failure = IOException("offline")))

        val r = h.check().run()

        assertTrue(r.forced.error is TraktError.Network)
        assertEquals(Fake.REFRESH, h.store.tokens!!.refreshToken)
        assertNull(r.automatic)
    }

    @Test
    fun `a wrong token that Trakt answers with 403 is replaced by the good pair again`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/oauth/token", tokenReply(1), tokenReply(2))
        h.host.on("GET", "/sync/history", history, Canned(status = 403))

        val r = h.check().run()

        assertEquals(TraktRenewalCheck.Step(false, TraktError.Forbidden, 1_700_000_002L + 604_800L), r.afterRejected)
        assertEquals("fake-access-2", h.store.tokens!!.accessToken)
        assertEquals("fake-refresh-2", h.store.tokens!!.refreshToken)
    }

    @Test
    fun `a failing test call after a renewal reports both`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/oauth/token", tokenReply(1), tokenReply(2))
        h.host.on("GET", "/sync/history", Canned(status = 200, body = "not json"))

        val r = h.check().run()

        assertTrue(r.automatic!!.renewed)
        assertTrue(r.automatic!!.error is TraktError.Malformed)
        assertNull(r.afterRejected)
    }

    @Test
    fun `not signed in fails the first step`() = runBlocking {
        val h = Harness(tokens = null)
        val r = h.check().run()
        assertEquals(TraktError.NotSignedIn, r.forced.error)
        assertTrue(h.host.seen.isEmpty())
    }

    @Test
    fun `no result ever contains a token`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/oauth/token", tokenReply(1), tokenReply(2), tokenReply(3))
        h.host.on("GET", "/sync/history", history, Canned(status = 401), history)
        val shown = h.check().run().toString()
        listOf(Fake.ACCESS, Fake.REFRESH, "fake-access-", "fake-refresh-", TraktRenewalCheck.WRONG_ACCESS_TOKEN).forEach {
            assertFalse("result shows $it", shown.contains(it))
        }
    }
}
