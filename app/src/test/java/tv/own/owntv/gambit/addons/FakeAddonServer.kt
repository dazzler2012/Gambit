package tv.own.owntv.gambit.addons

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Answers OkHttp requests from memory, as an application interceptor, so no request leaves the JVM.
 * Routes are matched on the full request URL; anything unrouted gets a 404.
 */
class FakeAddonServer {
    sealed interface Reply {
        data class Body(val json: String, val code: Int = 200) : Reply
        data class Fail(val error: IOException) : Reply
        data class Slow(val delayMs: Long, val json: String) : Reply

        /**
         * Holds the request until [release] is counted down, so a test decides when the answer arrives
         * instead of racing a clock. [entered] counts down when the request has reached the server.
         */
        class Gate(val json: String) : Reply {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
        }

        /** One reply per request to the same URL, in order; the last one repeats. */
        class Sequence(val replies: List<Reply>) : Reply {
            private var next = 0

            @Synchronized
            fun take(): Reply = replies[minOf(next++, replies.size - 1)]
        }
    }

    private val routes = mutableMapOf<String, Reply>()
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun on(url: String, reply: Reply) = apply { routes[url] = reply }
    fun onJson(url: String, json: String, code: Int = 200) = on(url, Reply.Body(json, code))
    fun onFixture(url: String, fixture: String) = onJson(url, Fixtures.read(fixture))

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> answer(chain) }).build()

    private fun answer(chain: Interceptor.Chain): Response {
        val url = chain.request().url.toString()
        requested += url
        var reply = routes[url] ?: Reply.Body("""{"err":"not found"}""", 404)
        if (reply is Reply.Sequence) reply = reply.take()
        val (code, json) = when (reply) {
            is Reply.Body -> reply.code to reply.json
            is Reply.Fail -> throw reply.error
            is Reply.Slow -> { Thread.sleep(reply.delayMs); 200 to reply.json }
            is Reply.Sequence -> error("nested sequence")
            is Reply.Gate -> {
                reply.entered.countDown()
                if (!reply.release.await(GATE_TIMEOUT_S, TimeUnit.SECONDS)) throw IOException("gate not released")
                200 to reply.json
            }
        }
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private companion object {
        const val GATE_TIMEOUT_S = 30L
    }
}
