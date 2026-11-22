package tv.own.owntv.gambit.playback

import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.model.AddonStream
import tv.own.owntv.gambit.addons.model.AddonSubtitle
import tv.own.owntv.gambit.addons.model.StreamBehaviorHints
import tv.own.owntv.gambit.ui.discovery.DiscoveryAccess
import tv.own.owntv.gambit.ui.discovery.canPlay

/**
 * The proxy against a fake addon CDN. `cdn.example` resolves to the loopback interface here, so no
 * request leaves the machine.
 */
class GambitStreamProxyTest {
    private val cdn = FakeCdn()
    private val loopbackDns = Dns { listOf(InetAddress.getLoopbackAddress()) }
    private val client = OkHttpClient.Builder().dns(loopbackDns).build()
    private val proxy = GambitStreamProxy(client)
    private val base get() = "http://cdn.example:${cdn.port}"
    private val filmUrl get() = "$base/dl/TOKEN-123/film.mkv"
    private val headers = mapOf("Authorization" to "Bearer SECRET-456", "User-Agent" to "Gambit-Test/1.0")

    @After
    fun tearDown() = cdn.close()

    private class Reply(val code: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun get(address: String, range: String? = null, method: String = "GET"): Reply {
        val c = URL(address).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.instanceFollowRedirects = false
        c.connectTimeout = 5_000
        c.readTimeout = 5_000
        range?.let { c.setRequestProperty("Range", it) }
        val code = c.responseCode
        val h = c.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
        val body = try {
            (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
        } catch (_: IOException) {
            ByteArray(0)
        }
        c.disconnect()
        return Reply(code, h, body)
    }

    @Test
    fun `the player gets a loopback address with nothing of the real one`() {
        val session = proxy.openSession()
        val address = session.add(filmUrl, headers, "video.mkv")
        assertTrue(address, address.startsWith("http://127.0.0.1:"))
        for (secret in listOf("TOKEN-123", "SECRET-456", "cdn.example", "film", "Bearer")) {
            assertFalse("$secret in $address", address.contains(secret))
            assertFalse(session.toString().contains(secret))
            assertFalse(proxy.toString().contains(secret))
        }
        session.close()
    }

    @Test
    fun `the server listens on the loopback interface only`() {
        val session = proxy.openSession()
        session.add(filmUrl, headers)
        val port = proxy.port!!
        // Reachable on loopback ...
        Socket(InetAddress.getLoopbackAddress(), port).close()
        // ... and bound to it: a wildcard bind would also accept the machine's other addresses.
        val field = GambitStreamProxy::class.java.getDeclaredField("server").apply { isAccessible = true }
        val server = field.get(proxy) as ServerSocket
        assertTrue(server.inetAddress.isLoopbackAddress)
        session.close()
    }

    @Test
    fun `a whole file passes through with the addon's headers`() {
        val session = proxy.openSession()
        val reply = get(session.add(filmUrl, headers))
        assertEquals(200, reply.code)
        assertArrayEquals(cdn.film, reply.body)
        assertEquals(cdn.film.size.toString(), reply.headers["Content-Length"])
        val seen = cdn.lastHeaders.getValue("/dl/TOKEN-123/film.mkv")
        assertEquals("Bearer SECRET-456", seen["authorization"])
        assertEquals("Gambit-Test/1.0", seen["user-agent"])
        assertEquals("identity", seen["accept-encoding"])
        session.close()
    }

    @Test
    fun `range requests pass through for seeking`() {
        val session = proxy.openSession()
        val address = session.add(filmUrl, headers)
        val reply = get(address, range = "bytes=100-199")
        assertEquals(206, reply.code)
        assertArrayEquals(cdn.film.copyOfRange(100, 200), reply.body)
        assertEquals("bytes 100-199/${cdn.film.size}", reply.headers["Content-Range"])
        assertEquals("bytes", reply.headers["Accept-Ranges"])
        assertEquals("bytes=100-199", cdn.lastHeaders.getValue("/dl/TOKEN-123/film.mkv")["range"])
        val tail = get(address, range = "bytes=900-")
        assertEquals(206, tail.code)
        assertArrayEquals(cdn.film.copyOfRange(900, cdn.film.size), tail.body)
        session.close()
    }

    @Test
    fun `HEAD answers headers only`() {
        val session = proxy.openSession()
        val reply = get(session.add(filmUrl, headers), method = "HEAD")
        assertEquals(200, reply.code)
        assertEquals(cdn.film.size.toString(), reply.headers["Content-Length"])
        session.close()
    }

    @Test
    fun `redirects are followed and never shown to the player`() {
        val session = proxy.openSession()
        val reply = get(session.add("$base/go/TOKEN-123", headers))
        assertEquals(200, reply.code)
        assertArrayEquals(cdn.film, reply.body)
        assertNull(reply.headers["Location"])
        session.close()
    }

    @Test
    fun `upstream errors keep their status and carry no body`() {
        val session = proxy.openSession()
        val reply = get(session.add("$base/missing/TOKEN-123", headers))
        assertEquals(404, reply.code)
        assertEquals(0, reply.body.size)
        session.close()
    }

    @Test
    fun `HLS is refused by the proxy`() {
        val session = proxy.openSession()
        assertEquals(415, get(session.add("$base/hls/TOKEN-123/list", headers)).code)
        session.close()
    }

    @Test
    fun `addresses die when the session closes, and the server stops`() {
        val session = proxy.openSession()
        val address = session.add(filmUrl, headers)
        assertEquals(200, get(address).code)
        val port = proxy.port!!
        session.close()
        assertNull(proxy.port)
        val refused = runCatching { Socket(InetAddress.getLoopbackAddress(), port).close() }.isFailure
        assertTrue("port $port still open", refused)
        // A new session gets new addresses; the old one stays dead.
        val again = proxy.openSession()
        again.add(filmUrl, headers)
        val oldOnNewPort = address.replace(":$port/", ":${proxy.port}/")
        assertEquals(404, get(oldOnNewPort).code)
        again.close()
    }

    @Test
    fun `closing a session cuts a transfer in progress`() {
        val session = proxy.openSession()
        val address = session.add("$base/slow/TOKEN-123", headers)
        val started = CountDownLatch(1)
        var finished = false
        val reader = thread {
            runCatching {
                val c = URL(address).openConnection() as HttpURLConnection
                c.readTimeout = 10_000
                c.inputStream.use { input ->
                    input.read()
                    started.countDown()
                    while (input.read() >= 0) Unit
                }
            }
            finished = true
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        session.close()
        reader.join(5_000)
        assertTrue(finished)
    }

    @Test
    fun `unknown addresses and other methods are refused`() {
        val session = proxy.openSession()
        val address = session.add(filmUrl, headers)
        val port = proxy.port!!
        assertEquals(404, get("http://127.0.0.1:$port/s/0123456789abcdef0123456789abcdef").code)
        assertEquals(404, get("http://127.0.0.1:$port/other").code)
        assertEquals(405, get(address, method = "DELETE").code)
        session.close()
    }

    @Test
    fun `subtitles go through the same session`() {
        val session = proxy.openSession()
        val reply = get(session.add("$base/subs/KEY-789/en.srt", emptyMap(), "sub0.srt"))
        assertEquals(200, reply.code)
        assertEquals(FakeCdn.SRT, String(reply.body))
        session.close()
    }

    @Test
    fun `the probe spots HLS by type or by its first bytes`() {
        val probe = HlsProbe(client)
        assertTrue(probe.isHls("$base/hls/TOKEN-123/list", headers))
        assertTrue(probe.isHls("$base/hidden/TOKEN-123", headers))
        assertTrue(probe.isHls("$base/bom/TOKEN-123", headers))
        assertFalse(probe.isHls(filmUrl, headers))
        assertFalse(probe.isHls("$base/missing/TOKEN-123", headers))
        assertTrue(StreamKind.isHlsUrl("https://cdn.example/a/b/master.m3u8?token=x"))
        assertFalse(StreamKind.isHlsUrl("https://cdn.example/a/film.mkv"))
    }

    @Test
    fun `a UTF-8 byte-order mark before the marker is still HLS`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertTrue(StreamKind.isHlsBody(bom + "#EXTM3U\n#EXT".toByteArray()))
        assertTrue(StreamKind.isHlsBody("\r\n#EXTM3U\n".toByteArray()))
        assertFalse(StreamKind.isHlsBody(bom + "1\n00:00:01,000".toByteArray()))
        assertFalse(StreamKind.isHlsBody(ByteArray(16)))
    }

    @Test
    fun `file extensions are kept for the player, nothing else`() {
        assertEquals("mkv", StreamKind.extension("Film.2001.1080p.mkv", "https://cdn.example/x/TOKEN"))
        assertEquals("mp4", StreamKind.extension(null, "https://cdn.example/dl/TOKEN-123/film.mp4?sig=abc"))
        assertNull(StreamKind.extension(null, "https://cdn.example/dl/TOKEN-123/"))
        assertEquals("video.mkv", GambitStreamProxy.safeSuffix("video.mkv"))
        assertEquals("ab", GambitStreamProxy.safeSuffix("a/b"))
    }

    @Test
    fun `keys for the player never hold the address`() {
        val key = AddonPlayMeta.contentKey("movie", "tt0000001")
        assertEquals("gambit-addon:movie:tt0000001", key)
        assertEquals("gambit-addon:series:tt9", AddonPlayMeta.trackKey("series", "tt9", "tt9:1:2"))
        assertEquals(key, AddonPlayMeta.trackKey("movie", "tt0000001", "tt0000001"))
    }

    @Test
    fun `a play request prints no secret`() {
        val stream = AddonStream(
            name = "AIO", url = filmUrl,
            behaviorHints = StreamBehaviorHints(requestHeaders = headers),
            subtitles = listOf(AddonSubtitle("en", "$base/subs/KEY-789/en.srt", "eng")),
        )
        val text = AddonPlayRequest(stream, "AIOStreams", "movie", "tt1", "tt1", "Film").toString()
        for (secret in listOf("TOKEN-123", "SECRET-456", "KEY-789")) assertFalse("$secret in $text", text.contains(secret))
    }

    @Test
    fun `kids profiles and unknown profiles can't play`() {
        assertTrue(canPlay(DiscoveryAccess.OPEN))
        assertFalse(canPlay(DiscoveryAccess.KIDS))
        assertFalse(canPlay(DiscoveryAccess.UNKNOWN))
    }
}

/** A tiny HTTP/1.1 server standing in for an addon's CDN. Supports Range, HEAD and one redirect. */
private class FakeCdn : AutoCloseable {
    val film = ByteArray(1000) { (it % 251).toByte() }
    val lastHeaders = ConcurrentHashMap<String, Map<String, String>>()
    private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    val port: Int = server.localPort

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) { runCatching { s.use { serve(it) } } }
            }
        }
    }

    override fun close() = server.close()

    private fun serve(s: Socket) {
        val input = BufferedInputStream(s.getInputStream())
        val first = readLine(input) ?: return
        val (method, path) = first.split(' ').let { it[0] to it[1] }
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        lastHeaders[path] = headers
        val out = s.getOutputStream()
        fun send(code: Int, reason: String, h: Map<String, String>, body: ByteArray) {
            val head = buildString {
                append("HTTP/1.1 $code $reason\r\n")
                h.forEach { (k, v) -> append("$k: $v\r\n") }
                append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            }
            out.write(head.toByteArray())
            if (method != "HEAD") out.write(body)
            out.flush()
        }
        when {
            path.startsWith("/dl/") -> {
                val range = headers["range"]?.removePrefix("bytes=")
                if (range == null) {
                    send(200, "OK", mapOf("Content-Type" to "video/x-matroska", "Accept-Ranges" to "bytes"), film)
                } else {
                    val from = range.substringBefore('-').toInt()
                    val to = range.substringAfter('-').ifEmpty { "${film.size - 1}" }.toInt()
                    send(
                        206, "Partial Content",
                        mapOf("Content-Type" to "video/x-matroska", "Accept-Ranges" to "bytes", "Content-Range" to "bytes $from-$to/${film.size}"),
                        film.copyOfRange(from, to + 1),
                    )
                }
            }
            path.startsWith("/go/") -> send(302, "Found", mapOf("Location" to "http://cdn.example:$port/dl/TOKEN-123/film.mkv"), ByteArray(0))
            path.startsWith("/hls/") -> send(200, "OK", mapOf("Content-Type" to "application/vnd.apple.mpegurl"), "#EXTM3U\n".toByteArray())
            path.startsWith("/hidden/") -> send(200, "OK", mapOf("Content-Type" to "application/octet-stream"), "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray())
            // A UTF-8 byte-order mark (EF BB BF) before the marker, sent as a plain file.
            path.startsWith("/bom/") -> send(
                200, "OK", mapOf("Content-Type" to "application/octet-stream"),
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray(),
            )
            path.startsWith("/subs/") -> send(200, "OK", mapOf("Content-Type" to "text/plain"), SRT.toByteArray())
            path.startsWith("/slow/") -> {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000000\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                repeat(10_000) {
                    out.write(ByteArray(1024))
                    out.flush()
                    Thread.sleep(5)
                }
            }
            else -> send(404, "Not Found", emptyMap(), "nope".toByteArray())
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    companion object {
        const val SRT = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"
    }
}
