package tv.own.owntv.gambit.stremio

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * The three Stremio calls the account import needs, as stremio-core makes them:
 * - [createLinkCode]: `GET link.stremio.com/api/v2/create?type=Create`
 * - [readLinkCode]: `GET link.stremio.com/api/v2/read?type=Read&code=…`
 * - [addonCollection]: `POST api.strem.io/api/addonCollectionGet` with the key in the JSON body
 *
 * There is deliberately no logout call: whether Stremio's logout ends only this session is not known.
 * The session key only ever goes in a request body, never a URL or header. Nothing here logs.
 *
 * @param baseClient the app's shared client; a copy with [timeoutMs] as the whole-call limit is used.
 * @param linkBase / [apiBase] are replaced with fake hosts in tests.
 */
class StremioClient(
    baseClient: OkHttpClient,
    linkBase: String = LINK_BASE,
    apiBase: String = API_BASE,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val http = baseClient.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
    private val link: HttpUrl = linkBase.toHttpUrl()
    private val api: HttpUrl = apiBase.toHttpUrl()

    suspend fun createLinkCode(): StremioResult<StremioLinkCode> {
        val url = link.newBuilder()
            .addPathSegments("api/v2/create")
            .addQueryParameter("type", "Create")
            .build()
        return send(Request.Builder().url(url).get()).then(StremioJson::linkCode)
    }

    suspend fun readLinkCode(code: StremioLinkCode): StremioResult<StremioLinkStatus> {
        val url = link.newBuilder()
            .addPathSegments("api/v2/read")
            .addQueryParameter("type", "Read")
            .addQueryParameter("code", code.code)
            .build()
        return send(Request.Builder().url(url).get()).then(StremioJson::linkStatus)
    }

    suspend fun addonCollection(key: StremioAuthKey): StremioResult<StremioAddonCollection> {
        val url = api.newBuilder().addPathSegments("api/addonCollectionGet").build()
        val body = JSONObject()
            .put("type", "AddonCollectionGet")
            .put("authKey", key.value)
            .put("update", true)
            .toString()
            .toRequestBody(JSON)
        return send(Request.Builder().url(url).post(body)).then(StremioJson::addonCollection)
    }

    private suspend fun send(builder: Request.Builder): StremioResult<String> {
        val call = http.newCall(builder.header("Accept", "application/json").build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(StremioResult.Err(failure(e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (!it.isSuccessful) StremioResult.Err(StremioError.HttpStatus(it.code))
                            else StremioResult.Ok(it.body.string())
                        }
                    } catch (e: IOException) {
                        StremioResult.Err(failure(e))
                    }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }
    }

    /** Exception messages can quote the request, so only the exception type is kept. */
    private fun failure(e: IOException): StremioError =
        if (e is InterruptedIOException) StremioError.Timeout else StremioError.Network(e.javaClass.simpleName)

    private inline fun <R> StremioResult<String>.then(parse: (String) -> StremioResult<R>): StremioResult<R> = when (this) {
        is StremioResult.Ok -> parse(value)
        is StremioResult.Err -> this
    }

    companion object {
        /**
         * The client the app gives Stremio: a fresh, plain OkHttp client, not the app's shared one, so no
         * interceptor and no event listener (logging or otherwise) is ever attached to calls that carry the
         * session key. Like the addon clients, it uses the system's DNS and proxy, not the app's own settings.
         */
        fun plainHttpClient(): OkHttpClient = OkHttpClient()

        const val LINK_BASE = "https://link.stremio.com"
        const val API_BASE = "https://api.strem.io"
        const val DEFAULT_TIMEOUT_MS = 15_000L
        private val JSON = "application/json".toMediaType()
    }
}
