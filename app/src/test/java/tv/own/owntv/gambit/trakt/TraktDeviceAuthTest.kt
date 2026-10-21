package tv.own.owntv.gambit.trakt

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktDeviceAuthTest {
    private val code = TraktDeviceCode(Fake.DEVICE_CODE, Fake.USER_CODE, "https://trakt.fake.test/activate", expiresInSeconds = 600, intervalSeconds = 5)

    private fun Harness.pollReplies(vararg replies: Canned) = host.on("POST", "/oauth/device/token", *replies)

    @Test
    fun `request code parses the device code and sends the required headers`() = runBlocking {
        val h = Harness(tokens = null)
        h.host.on("POST", "/oauth/device/code", Canned(body = Fake.fixture("device_code.json")))

        val result = h.client.deviceAuth.requestCode().getOrNull()!!

        assertEquals(Fake.DEVICE_CODE, result.deviceCode)
        assertEquals(Fake.USER_CODE, result.userCode)
        assertEquals("https://trakt.fake.test/activate", result.verificationUrl)
        assertEquals(600L, result.expiresInSeconds)
        assertEquals(5L, result.intervalSeconds)

        val sent = h.host.seen.single()
        assertEquals(Fake.CLIENT_ID, JSONObject(sent.body).getString("client_id"))
        assertEquals("2", sent.headers["trakt-api-version"])
        assertEquals(Fake.CLIENT_ID, sent.headers["trakt-api-key"])
        assertNull("no token before sign-in", sent.headers["Authorization"])
    }

    @Test
    fun `request code with malformed JSON is a typed error`() = runBlocking {
        val h = Harness(tokens = null)
        h.host.on("POST", "/oauth/device/code", Canned(body = Fake.fixture("truncated.json")))

        assertEquals(TraktError.Malformed("invalid JSON"), h.client.deviceAuth.requestCode().errorOrNull())
    }

    @Test
    fun `request code 403 is forbidden`() = runBlocking {
        val h = Harness(tokens = null)
        h.host.on("POST", "/oauth/device/code", Canned(status = 403))

        assertEquals(TraktError.Forbidden, h.client.deviceAuth.requestCode().errorOrNull())
    }

    @Test
    fun `pending then approved saves the tokens and polls at the interval`() = runBlocking {
        val h = Harness(tokens = null)
        h.pollReplies(Canned(status = 400), Canned(status = 400), Canned(body = Fake.fixture("token_ok.json")))

        val tokens = h.client.deviceAuth.awaitApproval(code).getOrNull()!!

        assertEquals(Fake.NEW_ACCESS, tokens.accessToken)
        assertEquals(Fake.NEW_REFRESH, tokens.refreshToken)
        assertEquals(Fake.NEW_EXPIRY, tokens.expiresAtEpochSeconds)
        assertEquals(tokens, h.store.tokens)
        assertEquals(listOf(5_000L, 5_000L, 5_000L), h.clock.sleeps)

        val body = JSONObject(h.host.seen.last().body)
        assertEquals(Fake.DEVICE_CODE, body.getString("code"))
        assertEquals(Fake.CLIENT_ID, body.getString("client_id"))
        assertEquals(Fake.CLIENT_SECRET, body.getString("client_secret"))
    }

    @Test
    fun `client secret is left out when the config has none`() = runBlocking {
        val h = Harness(tokens = null, config = TraktConfig(clientId = Fake.CLIENT_ID, baseUrl = Fake.BASE_URL))
        h.pollReplies(Canned(body = Fake.fixture("token_ok.json")))

        h.client.deviceAuth.awaitApproval(code)

        assertFalse(JSONObject(h.host.seen.single().body).has("client_secret"))
    }

    @Test
    fun `slow down lengthens the interval`() = runBlocking {
        val h = Harness(tokens = null)
        h.pollReplies(Canned(status = 429), Canned(status = 400), Canned(body = Fake.fixture("token_ok.json")))

        assertTrue(h.client.deviceAuth.awaitApproval(code) is TraktResult.Success)
        assertEquals(listOf(5_000L, 10_000L, 10_000L), h.clock.sleeps)
    }

    @Test
    fun `slow down honours a longer Retry-After`() = runBlocking {
        val h = Harness(tokens = null)
        h.pollReplies(Canned(status = 429, headers = mapOf("Retry-After" to "30")), Canned(body = Fake.fixture("token_ok.json")))

        h.client.deviceAuth.awaitApproval(code)

        assertEquals(listOf(5_000L, 30_000L), h.clock.sleeps)
    }

    @Test
    fun `expired code from Trakt`() = runBlocking {
        val h = Harness(tokens = null)
        h.pollReplies(Canned(status = 410))

        assertEquals(TraktError.CodeExpired, h.client.deviceAuth.awaitApproval(code).errorOrNull())
        assertNull(h.store.tokens)
    }

    @Test
    fun `stops polling once expires_in has passed`() = runBlocking {
        val h = Harness(tokens = null)
        h.pollReplies(Canned(status = 400))

        assertEquals(TraktError.CodeExpired, h.client.deviceAuth.awaitApproval(code).errorOrNull())
        // 600 s at 5 s: 119 polls, then the 120th wait reaches the deadline without polling.
        assertEquals(119, h.host.seen.size)
    }

    @Test
    fun `denied, already used and invalid codes end polling`() = runBlocking {
        for ((status, error) in listOf(418 to TraktError.CodeDenied, 409 to TraktError.CodeAlreadyUsed, 404 to TraktError.InvalidCode)) {
            val h = Harness(tokens = null)
            h.pollReplies(Canned(status = status))

            assertEquals("status $status", error, h.client.deviceAuth.awaitApproval(code).errorOrNull())
            assertEquals(1, h.host.seen.size)
            assertNull(h.store.tokens)
        }
    }

    @Test
    fun `timeouts, network errors and 5xx keep polling`() = runBlocking {
        val h = Harness(tokens = null)
        h.pollReplies(
            Canned(failure = SocketTimeoutException("fake")),
            Canned(failure = UnknownHostException("fake")),
            Canned(status = 503),
            Canned(body = Fake.fixture("token_ok.json")),
        )

        assertTrue(h.client.deviceAuth.awaitApproval(code) is TraktResult.Success)
        assertEquals(4, h.host.seen.size)
    }

    @Test
    fun `approved with an unreadable token is malformed, not saved`() = runBlocking {
        val h = Harness(tokens = null)
        h.pollReplies(Canned(body = Fake.fixture("token_missing_field.json")))

        assertEquals(TraktError.Malformed("missing 'created_at'"), h.client.deviceAuth.awaitApproval(code).errorOrNull())
        assertNull(h.store.tokens)
    }

    @Test
    fun `storage failure after approval is a typed error`() = runBlocking {
        val h = Harness(tokens = null)
        h.store.failWith = IllegalStateException("disk full")
        h.pollReplies(Canned(body = Fake.fixture("token_ok.json")))

        assertEquals(TraktError.Storage("IllegalStateException"), h.client.deviceAuth.awaitApproval(code).errorOrNull())
    }
}
