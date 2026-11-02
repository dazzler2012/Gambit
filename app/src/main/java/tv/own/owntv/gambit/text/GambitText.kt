package tv.own.owntv.gambit.text

import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * Provider names as people should read them. Some playlists send HTML entities in group and channel
 * names ("LIVE | &#127875; Halloween Movies"); the sync stores them as sent, so they are decoded where a
 * name is shown — the database keeps the provider's text, and a re-sync changes nothing.
 *
 * Only entities are decoded: numeric (&#127875; &#x1F383;) and the common named ones. Android's HTML
 * parser is not used on purpose: it would also act on anything in angle brackets and fold spaces.
 */
object GambitText {
    private val entity = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos|nbsp);""")
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to Char(0xA0).toString())

    /** [s] with its entities decoded; returned as is when it has none (the common case, no allocation). */
    fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        return entity.replace(s) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2).toIntOrNull(16)) ?: m.value
                body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull()) ?: m.value
                else -> named[body] ?: m.value
            }
        }
    }

    fun decodeOrNull(s: String?): String? = s?.let(::decode)

    private fun codePoint(cp: Int?): String? =
        cp?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }?.let { String(Character.toChars(it)) }
}

/** The channel's name as shown: [ChannelEntity.name] with entities decoded. */
val ChannelEntity.shownName: String get() = GambitText.decode(name)
