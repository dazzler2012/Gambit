package tv.own.owntv.gambit.trakt

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TraktErrorsTest {
    private suspend fun Harness.playbackError() = client.sync.playback().errorOrNull()

    @Test
    fun `statuses map to typed errors`() = runBlocking {
        val cases = listOf(
            403 to TraktError.Forbidden,
            404 to TraktError.NotFound,
            409 to TraktError.Conflict(null, null),
            422 to TraktError.Unprocessable,
            429 to TraktError.RateLimited(null),
            500 to TraktError.Server(500),
            502 to TraktError.Server(502),
            420 to TraktError.Http(420),
        )
        for ((status, error) in cases) {
            val h = Harness()
            h.host.on("GET", "/sync/playback", Canned(status = status, body = "not json"))
            assertEquals("status $status", error, h.playbackError())
        }
    }

    @Test
    fun `Retry-After in seconds`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/sync/playback", Canned(status = 429, headers = mapOf("Retry-After" to "12")))

        assertEquals(TraktError.RateLimited(12), h.playbackError())
    }

    @Test
    fun `Retry-After as an HTTP date`() = runBlocking {
        val h = Harness()
        // FakeClock starts at 2023-11-14T22:13:20Z; this date is 40 s later.
        h.host.on("GET", "/sync/playback", Canned(status = 429, headers = mapOf("Retry-After" to "Tue, 14 Nov 2023 22:14:00 GMT")))

        assertEquals(TraktError.RateLimited(40), h.playbackError())
    }

    @Test
    fun `unreadable Retry-After is unknown`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/sync/playback", Canned(status = 429, headers = mapOf("Retry-After" to "soon")))

        assertEquals(TraktError.RateLimited(null), h.playbackError())
    }

    @Test
    fun `timeouts and network failures`() = runBlocking {
        val cases = listOf(
            SocketTimeoutException("fake") to TraktError.Timeout,
            InterruptedIOException("timeout") to TraktError.Timeout,
            ConnectException("fake") to TraktError.Network("ConnectException"),
        )
        for ((failure, error) in cases) {
            val h = Harness()
            h.host.on("GET", "/sync/playback", Canned(failure = failure))
            assertEquals(error, h.playbackError())
        }
    }

    @Test
    fun `malformed bodies`() = runBlocking {
        val cases = listOf(
            Fake.fixture("truncated.json") to TraktError.Malformed("invalid JSON"),
            "" to TraktError.Malformed("empty body"),
            "{}" to TraktError.Malformed("invalid JSON"),
            "[42]" to TraktError.Malformed("array item 0 is not an object"),
            Fake.fixture("history_bad_item.json") to TraktError.Malformed("missing 'progress'"),
        )
        for ((body, error) in cases) {
            val h = Harness()
            h.host.on("GET", "/sync/playback", Canned(body = body))
            assertEquals("body $body", error, h.playbackError())
        }
    }

    @Test
    fun `scrobble conflict carries the times from the body`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/scrobble/stop", Canned(status = 409, body = Fake.fixture("scrobble_conflict.json")))

        val error = h.client.scrobbler.stop(TraktScrobbleItem.Movie(TraktIds(trakt = 1001)), 95.0).errorOrNull()

        assertEquals(TraktError.Conflict(Instant.parse("2026-10-21T18:00:00Z"), Instant.parse("2026-10-21T19:30:00Z")), error)
    }

    @Test
    fun `no secret, token or code ever appears in toString or error text`() = runBlocking {
        val secrets = listOf(Fake.CLIENT_ID, Fake.CLIENT_SECRET, Fake.ACCESS, Fake.REFRESH, Fake.DEVICE_CODE, Fake.USER_CODE)
        val texts = mutableListOf(
            Fake.config.toString(),
            Harness.validTokens().toString(),
            TraktDeviceCode(Fake.DEVICE_CODE, Fake.USER_CODE, "https://trakt.fake.test/activate", 600, 5).toString(),
        )
        // Every error the client produced for a reply that echoed the secrets back in its body.
        val echo = """{"error":"${Fake.ACCESS}","code":"${Fake.DEVICE_CODE}","watched_at":"${Fake.REFRESH}"}"""
        for (status in listOf(400, 401, 403, 404, 409, 422, 429, 500, 200)) {
            val h = Harness()
            h.host.on("GET", "/sync/playback", Canned(status = status, body = echo))
            h.host.on("POST", "/oauth/token", Canned(status = 503, body = echo))
            h.playbackError()?.let { texts += it.toString(); texts += it.description }
        }

        for (text in texts) for (secret in secrets) assertFalse("'$text' leaks a secret", text.contains(secret))
    }
}
