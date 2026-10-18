package tv.own.owntv.gambit.addons.model

/**
 * One entry of a stream response (`streams[]`). Exactly one source field is expected to be set
 * ([url], [infoHash], [ytId] or [externalUrl]); [support] says which kind it is and whether Gambit can play it.
 *
 * [url] may hold a debrid token, so [toString] leaves it out.
 */
data class AddonStream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val ytId: String? = null,
    val externalUrl: String? = null,
    val behaviorHints: StreamBehaviorHints = StreamBehaviorHints(),
    val subtitles: List<AddonSubtitle> = emptyList(),
) {
    val support: StreamSupport
        get() = when {
            url != null && isHttpUrl(url) -> StreamSupport.PLAYABLE
            url != null -> StreamSupport.UNSUPPORTED_SCHEME
            infoHash != null -> StreamSupport.TORRENT
            ytId != null -> StreamSupport.YOUTUBE
            externalUrl != null -> StreamSupport.EXTERNAL
            else -> StreamSupport.NO_SOURCE
        }

    val isPlayable: Boolean get() = support == StreamSupport.PLAYABLE

    override fun toString(): String =
        "AddonStream(name=$name, title=$title, support=$support, filename=${behaviorHints.filename})"

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)
}

/** How a stream can be used. Only [PLAYABLE] goes to the player; the rest are listed as unsupported. */
enum class StreamSupport {
    /** An http(s) url. */
    PLAYABLE,
    /** infoHash with no url: needs a torrent engine Gambit doesn't have. */
    TORRENT,
    YOUTUBE,
    EXTERNAL,
    /** A url that isn't http(s), e.g. magnet:. */
    UNSUPPORTED_SCHEME,
    NO_SOURCE,
}

data class StreamBehaviorHints(
    val notWebReady: Boolean = false,
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    /** Headers the player must send with the request (`proxyHeaders.request`). */
    val requestHeaders: Map<String, String> = emptyMap(),
)

/** A subtitle track, embedded in a stream or from a subtitles addon. */
data class AddonSubtitle(
    val id: String? = null,
    val url: String,
    val lang: String? = null,
) {
    override fun toString(): String = "AddonSubtitle(id=$id, lang=$lang)"
}
