package tv.own.owntv.gambit.trakt

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Obviously fake credentials and host. Nothing in these tests ever reaches api.trakt.tv. */
internal object Fake {
    const val CLIENT_ID = "fake-client-id-0000"
    const val CLIENT_SECRET = "fake-client-secret-0000"
    const val BASE_URL = "https://trakt.fake.test"
    const val ACCESS = "fake-access-token-AAAA"
    const val REFRESH = "fake-refresh-token-RRRR"
    const val NEW_ACCESS = "fake-access-token-BBBB"
    const val NEW_REFRESH = "fake-refresh-token-SSSS"
    const val DEVICE_CODE = "fake-device-code-DDDD"
    const val USER_CODE = "FAKE1234"

    /** Matches token_ok.json: created_at 1700000000 + expires_in 604800. */
    const val NEW_EXPIRY = 1_700_604_800L

    val config = TraktConfig(clientId = CLIENT_ID, clientSecret = CLIENT_SECRET, baseUrl = BASE_URL)

    fun fixture(name: String): String =
        requireNotNull(Fake::class.java.getResourceAsStream("/trakt/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }
}

/** A request the fake host saw, with the body read out. */
internal data class Seen(val method: String, val path: String, val query: String?, val headers: okhttp3.Headers, val body: String)

/** A canned reply, or a transport failure to throw instead. */
internal class Canned(
    val status: Int = 200,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    val failure: IOException? = null,
)

/**
 * An OkHttp interceptor that answers every request itself from a queue of replies, so no request
 * ever leaves the test. Replies are matched by "METHOD path"; each queued reply is used once, and the
 * last one for a route repeats.
 */
internal class FakeTraktHost : Interceptor {
    private val routes = mutableMapOf<String, ArrayDeque<Canned>>()
    val seen = mutableListOf<Seen>()

    fun on(method: String, path: String, vararg replies: Canned) {
        routes.getOrPut("$method $path") { ArrayDeque() }.addAll(replies)
    }

    fun seen(method: String, path: String) = seen.filter { it.method == method && it.path == path }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() } ?: ""
        val path = request.url.encodedPath
        seen += Seen(request.method, path, request.url.encodedQuery, request.headers, body)

        val queue = routes["${request.method} $path"] ?: error("no fake reply for ${request.method} $path")
        val canned = if (queue.size > 1) queue.removeFirst() else queue.first()
        canned.failure?.let { throw it }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(canned.status)
            .message("fake")
            .apply { canned.headers.forEach { (k, v) -> header(k, v) } }
            .body(canned.body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

/** An in-memory token store that can be told to fail. */
internal class MemoryTokenStore(var tokens: TraktTokens? = null) : TraktTokenStore {
    var failWith: Exception? = null
    var writes = 0

    override suspend fun read(): TraktTokens? {
        failWith?.let { throw it }
        return tokens
    }

    override suspend fun write(tokens: TraktTokens) {
        failWith?.let { throw it }
        writes++
        this.tokens = tokens
    }

    override suspend fun clear() {
        failWith?.let { throw it }
        tokens = null
    }
}

/** A clock the test moves; [sleep] advances it instead of waiting. */
internal class FakeClock(var nowMillis: Long = 1_700_000_000_000L) {
    val sleeps = mutableListOf<Long>()

    suspend fun sleep(millis: Long) {
        sleeps += millis
        nowMillis += millis
    }
}

internal class Harness(
    tokens: TraktTokens? = validTokens(),
    config: TraktConfig = Fake.config,
) {
    val host = FakeTraktHost()
    val store = MemoryTokenStore(tokens)
    val clock = FakeClock()
    val client = TraktClient(
        config = config,
        tokenStore = store,
        httpClient = OkHttpClient.Builder().addInterceptor(host).build(),
        nowMillis = { clock.nowMillis },
        sleep = { clock.sleep(it) },
    )

    companion object {
        /** Tokens that stay valid for a day after FakeClock's start. */
        fun validTokens() = TraktTokens(Fake.ACCESS, Fake.REFRESH, expiresAtEpochSeconds = 1_700_000_000L + 86_400L)

        /** Tokens that expire two minutes after FakeClock's start: inside the refresh margin. */
        fun expiringTokens() = TraktTokens(Fake.ACCESS, Fake.REFRESH, expiresAtEpochSeconds = 1_700_000_000L + 120L)
    }
}
