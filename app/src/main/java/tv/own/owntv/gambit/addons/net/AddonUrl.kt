package tv.own.owntv.gambit.addons.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A normalized addon address: the manifest URL without `/manifest.json`, split into [base] and an
 * optional [query] that is put back on every request (configured addons may keep settings there).
 *
 * Configured addon URLs hold debrid API keys, so this class is a secret: [toString] shows the host only.
 * Read [base]/[query] only to make requests, never to log or display.
 */
class AddonUrl private constructor(
    val base: String,
    val query: String?,
    val host: String,
) {
    val manifestUrl: String get() = withQuery("$base/manifest.json")

    fun catalogUrl(type: String, catalogId: String, extra: CatalogExtraArgs = CatalogExtraArgs()): String {
        val extraPath = extra.toPathSegment()
        val path = if (extraPath == null) "catalog/${encode(type)}/${encode(catalogId)}"
        else "catalog/${encode(type)}/${encode(catalogId)}/$extraPath"
        return resource(path)
    }

    fun metaUrl(type: String, id: String): String = resource("meta/${encode(type)}/${encode(id)}")

    fun streamUrl(type: String, videoId: String): String = resource("stream/${encode(type)}/${encode(videoId)}")

    fun subtitlesUrl(type: String, id: String): String = resource("subtitles/${encode(type)}/${encode(id)}")

    private fun resource(path: String) = withQuery("$base/$path.json")

    private fun withQuery(url: String) = if (query.isNullOrEmpty()) url else "$url?$query"

    override fun equals(other: Any?): Boolean =
        other is AddonUrl && other.base == base && other.query == query

    override fun hashCode(): Int = 31 * base.hashCode() + (query?.hashCode() ?: 0)

    override fun toString(): String = "AddonUrl($host)"

    companion object {
        /**
         * Normalizes what a user typed or scanned: `stremio://` becomes `https://`, the scheme must be
         * http(s), and `/manifest.json` and trailing slashes are dropped. The query is kept. Returns null
         * for anything that isn't a usable URL.
         */
        fun parse(raw: String): AddonUrl? {
            var text = raw.trim()
            if (text.startsWith("stremio://", ignoreCase = true)) text = "https://" + text.substring("stremio://".length)
            if (!text.startsWith("http://", ignoreCase = true) && !text.startsWith("https://", ignoreCase = true)) return null
            text = text.substringBefore('#')
            val queryStart = text.indexOf('?')
            var path = if (queryStart >= 0) text.substring(0, queryStart) else text
            val query = if (queryStart >= 0) text.substring(queryStart + 1).takeIf { it.isNotEmpty() } else null
            path = path.trimEnd('/')
            if (path.endsWith("/manifest.json", ignoreCase = true)) path = path.dropLast("/manifest.json".length)
            path = path.trimEnd('/')
            val parsed = path.toHttpUrlOrNull() ?: return null
            if (parsed.host.isEmpty()) return null
            return AddonUrl(base = path, query = query, host = parsed.host)
        }

        /**
         * Percent-encodes one path value the way JavaScript's encodeURIComponent does, which is what the
         * Stremio clients use for types, ids and extra values. Episode ids like `tt123:1:2` become
         * `tt123%3A1%3A2`; addon servers decode the path before routing.
         */
        internal fun encode(value: String): String {
            val out = StringBuilder()
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in UNRESERVED)) out.append(c.toChar())
                else out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
            return out.toString()
        }

        private const val UNRESERVED = "-_.!~*'()"
        private const val HEX = "0123456789ABCDEF"
    }
}

/**
 * The extra arguments of a catalog request. Only set values are sent, in the order search, genre, skip.
 */
data class CatalogExtraArgs(
    val search: String? = null,
    val genre: String? = null,
    val skip: Int? = null,
) {
    internal fun toPathSegment(): String? {
        val parts = buildList {
            search?.takeIf { it.isNotBlank() }?.let { add("search=" + AddonUrl.encode(it)) }
            genre?.takeIf { it.isNotBlank() }?.let { add("genre=" + AddonUrl.encode(it)) }
            skip?.takeIf { it > 0 }?.let { add("skip=$it") }
        }
        return if (parts.isEmpty()) null else parts.joinToString("&")
    }
}
