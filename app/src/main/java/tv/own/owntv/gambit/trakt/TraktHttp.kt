package tv.own.owntv.gambit.trakt

import java.io.IOException
import java.io.InterruptedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * The one place this package talks to Trakt. Adds the required headers, turns transport failures and
 * HTTP statuses into [TraktError]s, and never logs anything.
 */
internal class TraktHttp(
    private val config: TraktConfig,
    private val client: OkHttpClient,
    private val nowMillis: () -> Long,
) {
    class Reply(val status: Int, val body: String, val headers: Headers)

    private val base: HttpUrl = config.baseUrl.toHttpUrl()

    /**
     * Sends one request. 2xx replies come back as [TraktResult.Success]; any other status is mapped
     * with [errorFor] unless [acceptAnyStatus] is set, in which case the caller reads the status itself.
     */
    suspend fun send(
        method: String,
        path: String,
        body: Any? = null,
        accessToken: String? = null,
        query: Map<String, String> = emptyMap(),
        acceptAnyStatus: Boolean = false,
    ): TraktResult<Reply> {
        val request = try {
            buildRequest(method, path, body, accessToken, query)
        } catch (e: IllegalArgumentException) {
            return TraktResult.Failure(TraktError.Network(e.javaClass.simpleName))
        }
        val reply = when (val sent = execute(request)) {
            is TraktResult.Failure -> return sent
            is TraktResult.Success -> sent.value
        }
        return if (acceptAnyStatus || reply.status in 200..299) {
            TraktResult.Success(reply)
        } else {
            TraktResult.Failure(errorFor(reply))
        }
    }

    private fun buildRequest(
        method: String,
        path: String,
        body: Any?,
        accessToken: String?,
        query: Map<String, String>,
    ): Request {
        val url = base.newBuilder().apply {
            path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val requestBody = body?.toString()?.toRequestBody(JSON)
        return Request.Builder()
            .url(url)
            .method(method, requestBody ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(JSON) else null)
            .header("Content-Type", "application/json")
            .header("trakt-api-version", TraktConfig.API_VERSION)
            .header("trakt-api-key", config.clientId)
            .header("User-Agent", config.userAgent)
            .apply { if (accessToken != null) header("Authorization", "Bearer $accessToken") }
            .build()
    }

    private suspend fun execute(request: Request): TraktResult<Reply> = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(TraktResult.Failure(transportError(e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { TraktResult.Success(Reply(it.code, it.body.string(), it.headers)) }
                    } catch (e: IOException) {
                        TraktResult.Failure(transportError(e))
                    }
                    if (cont.isActive) cont.resume(result)
                }
            },
        )
    }

    private fun transportError(e: IOException): TraktError =
        if (e is InterruptedIOException) TraktError.Timeout else TraktError.Network(e.javaClass.simpleName)

    fun errorFor(reply: Reply): TraktError = when (val code = reply.status) {
        401 -> TraktError.Unauthorized
        403 -> TraktError.Forbidden
        404 -> TraktError.NotFound
        409 -> conflict(reply.body)
        422 -> TraktError.Unprocessable
        429 -> TraktError.RateLimited(retryAfterSeconds(reply.headers))
        in 500..599 -> TraktError.Server(code)
        else -> TraktError.Http(code)
    }

    /** Retry-After is either whole seconds or an HTTP date. Unknown when absent or unreadable. */
    fun retryAfterSeconds(headers: Headers): Long? {
        val raw = headers["Retry-After"]?.trim() ?: return null
        raw.toLongOrNull()?.let { return it.coerceAtLeast(0) }
        return try {
            val at = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            ((at - nowMillis()) / 1000).coerceAtLeast(0)
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun conflict(body: String): TraktError.Conflict {
        val json = try {
            JSONObject(body)
        } catch (e: RuntimeException) {
            return TraktError.Conflict(null, null)
        }
        return TraktError.Conflict(json.instantOrNull("watched_at"), json.instantOrNull("expires_at"))
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}

/**
 * Runs a block that reads JSON and turns any parse problem into [TraktError.Malformed]. The reason
 * comes from [TraktJsonException] (written here) or is a fixed string, never the body or the
 * underlying exception's message, which can quote the response.
 */
internal inline fun <T> parsing(block: () -> T): TraktResult<T> = try {
    TraktResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: TraktJsonException) {
    TraktResult.Failure(TraktError.Malformed(e.reason))
} catch (e: org.json.JSONException) {
    TraktResult.Failure(TraktError.Malformed("invalid JSON"))
} catch (e: RuntimeException) {
    TraktResult.Failure(TraktError.Malformed("unexpected shape"))
}

/** Runs a token-store call and turns anything it throws into [TraktError.Storage]. */
internal suspend inline fun <T> storing(crossinline block: suspend () -> T): TraktResult<T> = try {
    TraktResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    TraktResult.Failure(TraktError.Storage(e.javaClass.simpleName))
}
