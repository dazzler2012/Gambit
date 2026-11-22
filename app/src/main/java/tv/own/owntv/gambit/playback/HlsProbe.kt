package tv.own.owntv.gambit.playback

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Looks at the first bytes of a stream before it is played. Runs off the main thread. Nothing logs, and
 * the address and headers are only used for the one request.
 */
class HlsProbe(http: OkHttpClient) {
    private val client = http.newBuilder().followRedirects(true).followSslRedirects(true).build()

    /** Asks for the first bytes: an HLS playlist is refused even when its address doesn't say so. */
    fun isHls(url: String, headers: Map<String, String>): Boolean {
        val request = try {
            Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> runCatching { header(k, v) } }
                header(RANGE, PROBE_RANGE)
                header(ACCEPT_ENCODING, IDENTITY)
            }.build()
        } catch (_: IllegalArgumentException) {
            return false
        }
        return try {
            client.newCall(request).execute().use { response ->
                if (StreamKind.isHlsContentType(response.header(CONTENT_TYPE))) return true
                if (!response.isSuccessful) return false
                val start = response.body.source().let { src ->
                    src.request(PROBE_BYTES.toLong())
                    val n = minOf(PROBE_BYTES.toLong(), src.buffer.size)
                    src.buffer.readByteArray(n)
                }
                StreamKind.isHlsBody(start)
            }
        } catch (_: IOException) {
            false // the player tries it and shows its own error
        }
    }

    private companion object {
        const val PROBE_BYTES = 16
        const val PROBE_RANGE = "bytes=0-15"
        const val RANGE = "Range"
        const val ACCEPT_ENCODING = "Accept-Encoding"
        const val IDENTITY = "identity"
        const val CONTENT_TYPE = "Content-Type"
    }
}
