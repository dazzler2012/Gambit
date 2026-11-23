package tv.own.owntv.gambit.stremio

import java.io.IOException
import java.util.Collections
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Fake hosts and values. Nothing in these tests reaches a Stremio server. */
internal object FakeStremio {
    const val LINK_BASE = "https://link.stremio.example"
    const val API_BASE = "https://api.stremio.example"

    /** A planted key: tests check it never shows up anywhere it shouldn't. */
    const val KEY = "SENTINEL-auth-key-7f3a9c"

    fun client(host: FakeStremioHost) = StremioClient(host.client, LINK_BASE, API_BASE)
}

/** One request the fake host saw, with its body read out. */
internal data class SeenRequest(val method: String, val url: String, val headers: Headers, val body: String)

/**
 * Answers every request itself as an OkHttp interceptor, so nothing leaves the JVM. Replies are
 * matched by "METHOD path" (no query); anything unrouted gets a 404.
 */
internal class FakeStremioHost : Interceptor {
    class Reply(val code: Int = 200, val body: String = "", val failure: IOException? = null)

    private val routes = mutableMapOf<String, Reply>()
    val seen: MutableList<SeenRequest> = Collections.synchronizedList(mutableListOf())

    fun on(method: String, path: String, reply: Reply) = apply { routes["$method $path"] = reply }
    fun onJson(method: String, path: String, body: String, code: Int = 200) = on(method, path, Reply(code, body))

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() } ?: ""
        seen += SeenRequest(request.method, request.url.toString(), request.headers, body)
        val reply = routes["${request.method} ${request.url.encodedPath}"] ?: Reply(404, """{"error":"not routed"}""")
        reply.failure?.let { throw it }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(reply.code)
            .message(if (reply.code == 200) "OK" else "Error")
            .body(reply.body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
