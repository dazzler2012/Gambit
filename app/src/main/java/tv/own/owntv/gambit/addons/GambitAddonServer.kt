package tv.own.owntv.gambit.addons

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.own.owntv.R
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * "Add an addon from your phone": a small web page on the home network, open only while the TV shows its
 * QR code. Modelled on OwnTV's companion server (core, which has no addon mode and can't be extended).
 *
 *  - Plain HTTP on the LAN (a phone browser can't encrypt for an http page); only private / link-local
 *    addresses are served.
 *  - A fresh 6-digit PIN each time, shown on the TV and not in the QR; 5 wrong PINs lock it.
 *  - The address is sent once, in a POST body (never in a URL, so no browser or router keeps it); the
 *    body is capped at 8 KB. One successful add closes it; it also closes after 10 minutes.
 *  - Nothing is logged, and replies never echo the address.
 */
class GambitAddonServer(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data class Open(val address: String, val pin: String) : State
        data class Added(val name: String) : State
        data object Locked : State
        data object Failed : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var socket: ServerSocket? = null
    @Volatile private var pin: String = ""
    @Volatile private var wrongPins = 0

    /** Open the page at [address] (the TV's LAN address with the port); [onAddress] adds what arrives. */
    fun start(address: String, onAddress: suspend (String) -> AddonAddResult) {
        stop()
        val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) } }.getOrNull()
        if (s == null) { _state.value = State.Failed; return }
        socket = s
        pin = (SecureRandom().nextInt(900_000) + 100_000).toString()
        wrongPins = 0
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        _state.value = State.Open(address, pin)
        sc.launch { delay(OPEN_FOR_MS); if (_state.value is State.Open) stop() }
        sc.launch {
            while (isActive) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                launch { client.use { handle(it, onAddress) } }
            }
        }
    }

    /** Close the page; the last result (added / locked) stays readable until the next [start]. */
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        scope?.coroutineContext?.get(Job)?.cancel()
        scope = null
        if (_state.value is State.Open) _state.value = State.Idle
    }

    fun reset() { stop(); _state.value = State.Idle }

    private suspend fun handle(client: Socket, onAddress: suspend (String) -> AddonAddResult) {
        client.soTimeout = 15_000
        if (!isLan(client.inetAddress)) return
        val req = read(client.getInputStream()) ?: return send(client, 400, TEXT, "")
        when {
            req.method == GET && (req.path == "/" || req.path.startsWith("/?")) -> send(client, 200, HTML, page())
            req.method == POST && req.path == "/add" -> {
                if (_state.value !is State.Open) return send(client, 409, JSON, reply(false, MSG_CLOSED))
                val form = parseForm(req.body)
                val given = form[F_PIN].orEmpty()
                if (!MessageDigest.isEqual(given.toByteArray(), pin.toByteArray())) {
                    delay(500)
                    if (++wrongPins >= MAX_WRONG_PINS) { send(client, 403, JSON, reply(false, MSG_LOCKED)); _state.value = State.Locked; stop(); return }
                    return send(client, 403, JSON, reply(false, MSG_PIN))
                }
                when (val r = onAddress(form[F_URL].orEmpty())) {
                    is AddonAddResult.Added -> {
                        send(client, 200, JSON, reply(true, r.name))
                        _state.value = State.Added(r.name)
                        stop()
                    }
                    is AddonAddResult.Failed -> send(client, 422, JSON, reply(false, r.error.name))
                }
            }
            else -> send(client, 404, TEXT, "")
        }
    }

    private class Req(val method: String, val path: String, val body: String)

    /** The request line, headers and a body of at most [MAX_BODY] bytes; null when it's malformed or too big. */
    private fun read(input: InputStream): Req? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0 || head.length > MAX_HEAD) return null
            head.append(b.toChar())
        }
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2) return null
        val length = lines.drop(1).firstOrNull { it.startsWith(CONTENT_LENGTH, ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) return null
        val body = ByteArray(length)
        var got = 0
        while (got < length) { val n = input.read(body, got, length - got); if (n < 0) return null; got += n }
        return Req(parts[0], parts[1], String(body, Charsets.UTF_8))
    }

    private fun parseForm(body: String): Map<String, String> = body.split('&').mapNotNull { pair ->
        val i = pair.indexOf('=')
        if (i <= 0) null else runCatching { URLDecoder.decode(pair.substring(0, i), UTF8) to URLDecoder.decode(pair.substring(i + 1), UTF8) }.getOrNull()
    }.toMap()

    private fun page(): String = context.resources.openRawResource(R.raw.gambit_addon_page).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun reply(ok: Boolean, text: String): String =
        org.json.JSONObject().put(F_OK, ok).put(F_MESSAGE, text).toString()

    private fun send(client: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        runCatching { client.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(bytes); flush() } }
    }

    private fun isLan(a: InetAddress): Boolean = a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress

    companion object {
        /** OwnTV's companion page uses 8089; this one sits beside it. */
        const val PORT = 8090
        private const val OPEN_FOR_MS = 10 * 60_000L
        private const val MAX_WRONG_PINS = 5
        private const val MAX_HEAD = 8_192
        private const val MAX_BODY = 8_192
        private const val GET = "GET"
        private const val POST = "POST"
        private const val CONTENT_LENGTH = "Content-Length"
        private const val UTF8 = "UTF-8"
        private const val HTML = "text/html; charset=utf-8"
        private const val JSON = "application/json; charset=utf-8"
        private const val TEXT = "text/plain; charset=utf-8"
        private const val F_PIN = "pin"
        private const val F_URL = "url"
        private const val F_OK = "ok"
        private const val F_MESSAGE = "message"
        private const val MSG_PIN = "PIN"
        private const val MSG_LOCKED = "LOCKED"
        private const val MSG_CLOSED = "CLOSED"
    }
}
