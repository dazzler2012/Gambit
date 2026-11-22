package tv.own.owntv.gambit.playback

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * A small HTTP server on the loopback interface that stands between the player and an addon stream.
 * The player is only ever given `http://127.0.0.1:<port>/s/<token>/…`; the real address and its request
 * headers (which can carry debrid tokens) stay in this object, so nothing in the player, its logs, its
 * error file or its Stream info can show or save them.
 *
 * Each play opens a [Session]; every address in it (the video and its subtitles) works only until the
 * session is closed, which also cuts any transfer in progress. When no session is open the server is shut.
 * Range requests pass through for seeking; redirects are followed here; HLS playlists are refused (their
 * segment addresses would reach the player directly). Nothing here logs.
 */
class GambitStreamProxy(http: OkHttpClient) {

    // Our own Range and encoding go upstream exactly as asked; redirects are followed for the player.
    private val http = http.newBuilder().followRedirects(true).followSslRedirects(true).build()

    /** One proxied address. Holds the real URL and headers; never printed. */
    private class Target(val url: String, val headers: Map<String, String>) {
        val sockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
        override fun toString(): String = "Target(redacted)"
    }

    /** The addresses of one play. [close] makes all of them dead at once. */
    inner class Session internal constructor() {
        private val tokens = ConcurrentHashMap.newKeySet<String>()
        @Volatile var isOpen: Boolean = true
            private set

        /**
         * A loopback address for [url], sent upstream with [headers]. [suffix] (a file name such as
         * `video.mkv`) only helps the player guess the container; it carries nothing of [url].
         */
        fun add(url: String, headers: Map<String, String> = emptyMap(), suffix: String? = null): String {
            check(isOpen) { "session closed" }
            val token = newToken()
            targets[token] = Target(url, headers)
            tokens += token
            val port = ensureServer()
            return "http://127.0.0.1:$port/s/$token" + (suffix?.let { "/${safeSuffix(it)}" }.orEmpty())
        }

        fun close() {
            if (!isOpen) return
            isOpen = false
            for (token in tokens) targets.remove(token)?.sockets?.forEach { runCatching { it.close() } }
            tokens.clear()
            sessionClosed(this)
        }

        override fun toString(): String = "Session(open=$isOpen, addresses=${tokens.size})"
    }

    private val targets = ConcurrentHashMap<String, Target>()
    private val sessions = ConcurrentHashMap.newKeySet<Session>()
    private val random = SecureRandom()
    private val lock = Any()
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    fun openSession(): Session = Session().also { sessions += it }

    /** The port while the server runs, else null. For tests and checks. */
    val port: Int? get() = synchronized(lock) { server?.localPort }

    private fun sessionClosed(session: Session) {
        sessions -= session
        if (sessions.isEmpty()) shutDown()
    }

    private fun ensureServer(): Int = synchronized(lock) {
        server?.let { return it.localPort }
        val socket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
        val executor = Executors.newCachedThreadPool { r -> Thread(r, THREAD_NAME).apply { isDaemon = true } }
        server = socket
        pool = executor
        executor.execute { acceptLoop(socket, executor) }
        socket.localPort
    }

    private fun shutDown() = synchronized(lock) {
        if (sessions.isNotEmpty()) return@synchronized
        runCatching { server?.close() }
        pool?.shutdown()
        server = null
        pool = null
    }

    private fun acceptLoop(socket: ServerSocket, executor: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            try {
                executor.execute { serve(client) }
            } catch (_: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        var target: Target? = null
        try {
            client.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream(), BUFFER)
            val request = readRequest(input) ?: return
            if (request.method != GET && request.method != HEAD) return status(output, 405)
            val token = request.path.removePrefix(PATH_PREFIX).substringBefore('/').substringBefore('?')
            if (!request.path.startsWith(PATH_PREFIX)) return status(output, 404)
            target = targets[token] ?: return status(output, 404)
            target.sockets += client
            if (!targets.containsKey(token)) return status(output, 404) // closed while we were reading
            val upstream = Request.Builder().url(target.url).apply {
                target.headers.forEach { (k, v) -> runCatching { header(k, v) } }
                // Bytes as the server sends them: a decompressed body would no longer match Content-Length.
                header(ACCEPT_ENCODING, IDENTITY)
                request.range?.let { header(RANGE, it) }
                if (request.method == HEAD) head()
            }.build()
            val response = try {
                http.newCall(upstream).execute()
            } catch (_: IOException) {
                return status(output, 502)
            } catch (_: IllegalArgumentException) {
                return status(output, 502)
            }
            response.use { relay(it, request.method == HEAD, output) }
        } catch (_: IOException) {
            // The player went away or the session was closed; nothing to report.
        } finally {
            target?.sockets?.remove(client)
            runCatching { client.close() }
        }
    }

    private fun relay(response: Response, head: Boolean, output: OutputStream) {
        if (StreamKind.isHlsContentType(response.header(CONTENT_TYPE))) return status(output, 415)
        // An error page can quote the address it was asked for: pass the status on, never the body.
        if (response.code >= 400) return status(output, response.code)
        val head1 = StringBuilder()
            .append(HTTP_VERSION).append(' ').append(response.code).append(' ').append(reason(response.code)).append(CRLF)
        for (name in PASSED_HEADERS) response.header(name)?.let { head1.append(name).append(HEADER_SEP).append(clean(it)).append(CRLF) }
        head1.append(CONNECTION_CLOSE).append(CRLF).append(CRLF)
        output.write(head1.toString().toByteArray(Charsets.ISO_8859_1))
        if (!head) {
            response.body.byteStream().use { body ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = body.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
            }
        }
        output.flush()
    }

    private fun status(output: OutputStream, code: Int) {
        val text = HTTP_VERSION + " " + code + " " + reason(code) + CRLF + CONTENT_LENGTH_ZERO + CRLF + CONNECTION_CLOSE + CRLF + CRLF
        runCatching {
            output.write(text.toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }
    }

    private class ProxyRequest(val method: String, val path: String, val range: String?)

    /** The request line and the one header we use. Anything oversized or malformed is dropped. */
    private fun readRequest(input: InputStream): ProxyRequest? {
        val first = readLine(input) ?: return null
        val parts = first.split(' ')
        if (parts.size < 2) return null
        var range: String? = null
        var count = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (++count > MAX_HEADERS) return null
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals(RANGE, ignoreCase = true)) range = line.substring(colon + 1).trim()
        }
        return ProxyRequest(parts[0], parts[1], range?.takeIf { RANGE_VALUE.matches(it) })
    }

    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) return out.toString().trimEnd('\r')
            if (out.length >= MAX_LINE) return null
            out.append(c.toChar())
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString(separator = "") { b -> HEX[(b.toInt() shr 4) and 0xF].toString() + HEX[b.toInt() and 0xF] }
    }

    private fun clean(value: String): String = value.replace('\r', ' ').replace('\n', ' ')

    companion object {
        private const val BACKLOG = 16
        private const val BUFFER = 64 * 1024
        private const val READ_TIMEOUT_MS = 30_000
        private const val TOKEN_BYTES = 16
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 64
        private const val HEX = "0123456789abcdef"
        private const val THREAD_NAME = "gambit-stream-proxy"
        private const val PATH_PREFIX = "/s/"
        private const val GET = "GET"
        private const val HEAD = "HEAD"
        private const val RANGE = "Range"
        private const val ACCEPT_ENCODING = "Accept-Encoding"
        private const val IDENTITY = "identity"
        private const val CONTENT_TYPE = "Content-Type"
        private const val HTTP_VERSION = "HTTP/1.1"
        private const val CRLF = "\r\n"
        private const val HEADER_SEP = ": "
        private const val CONNECTION_CLOSE = "Connection: close"
        private const val CONTENT_LENGTH_ZERO = "Content-Length: 0"
        private val RANGE_VALUE = Regex("""bytes=\d*-\d*(,\s*\d*-\d*)*""")
        private val SUFFIX = Regex("""[^A-Za-z0-9._-]""")

        /** Headers the player may see. Nothing that can name the real address (Location, Set-Cookie …). */
        private val PASSED_HEADERS = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")

        private fun reason(code: Int): String = when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            415 -> "Unsupported Media Type"
            416 -> "Range Not Satisfiable"
            502 -> "Bad Gateway"
            else -> "Status"
        }

        internal fun safeSuffix(name: String): String = SUFFIX.replace(name, "").take(40).ifEmpty { "media" }
    }
}

/** What kind of stream an address or response is. HLS can't go through the proxy safely. */
object StreamKind {
    private val HLS_PATH = Regex("""(?i)\.m3u8?$""")
    private const val HLS_MARKER = "#EXTM3U"
    private const val MPEGURL = "mpegurl"

    /** The address's path ends in .m3u8 / .m3u (query and fragment ignored). */
    fun isHlsUrl(url: String): Boolean = HLS_PATH.containsMatchIn(url.substringBefore('#').substringBefore('?'))

    fun isHlsContentType(contentType: String?): Boolean = contentType?.contains(MPEGURL, ignoreCase = true) == true

    /** The first bytes hold an M3U playlist marker, anywhere in them (a byte-order mark or blank lines may come first). */
    fun isHlsBody(start: ByteArray): Boolean = String(start, Charsets.ISO_8859_1).contains(HLS_MARKER)

    /** File extension for the player's benefit, from a file name or the address's path; null if none fits. */
    fun extension(filename: String?, url: String): String? {
        val candidates = listOfNotNull(filename, url.substringBefore('#').substringBefore('?'))
        for (c in candidates) {
            val ext = c.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "").lowercase()
            if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) return ext
        }
        return null
    }
}
