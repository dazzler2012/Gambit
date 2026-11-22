package tv.own.owntv.gambit.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.data.SourcedStream
import tv.own.owntv.gambit.addons.data.StreamResults
import tv.own.owntv.gambit.addons.net.AddonError

/**
 * What the stream list is for: a movie (the title's id) or one episode (its video id, e.g. `tt1:1:2`).
 * Ids are not secret; the stream addresses that come back are.
 */
data class StreamTarget(
    val type: String,
    val videoId: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    /** The title's own id (the show's, for an episode). */
    val metaId: String = videoId,
    val year: String? = null,
)

/** A quality read from a stream's name or filename. The screen words it. */
enum class StreamQuality { UHD, FHD, HD, SD }

/**
 * One stream as the screen shows it: display text only. The address and headers stay in [StreamBoard];
 * [key] is how the row is matched back to its stream (Stage 5).
 */
data class StreamRow(
    val key: Int,
    val nameLines: List<String>,
    val detailLines: List<String>,
    val quality: StreamQuality?,
    val sizeBytes: Long?,
    val addonName: String,
    /** Looks like Dolby Vision Profile 7 ([StreamRows.likelyDvProfile7]): shown with a warning, listed lower. */
    val mayNotPlay: Boolean = false,
)

sealed interface StreamFeed {
    data object Loading : StreamFeed

    /** Every addon failed and none returned anything. */
    data class Failed(val error: AddonError) : StreamFeed

    /**
     * [rows] in addon order, then each addon's own order; [unplayable] counts streams Gambit can't play
     * (torrent, YouTube, website); [errors] are addons that failed while others answered.
     */
    data class Loaded(val rows: List<StreamRow>, val unplayable: Int, val errors: List<AddonError>) : StreamFeed
}

data class StreamListState(val target: StreamTarget, val feed: StreamFeed = StreamFeed.Loading)

object StreamRows {
    /**
     * Display rows for the playable streams, keyed by their place in [playable]. Rows that look like Dolby
     * Vision Profile 7 are moved below the other streams of their quality ([demoteMayNotPlay]). Nothing
     * from the address is kept.
     */
    fun rows(playable: List<SourcedStream>): List<StreamRow> = demoteMayNotPlay(
        playable.mapIndexed { index, sourced ->
            val s = sourced.stream
            val texts = listOfNotNull(s.name, s.title, s.description, s.behaviorHints.filename)
            StreamRow(
                key = index,
                nameLines = lines(s.name),
                detailLines = lines(s.title ?: s.description),
                quality = quality(texts),
                sizeBytes = s.behaviorHints.videoSize?.takeIf { it > 0 },
                addonName = sourced.addonName,
                mayNotPlay = likelyDvProfile7(texts),
            )
        },
    )

    /**
     * Each flagged row goes right after the last unflagged row of the same quality (after earlier flagged
     * ones already moved there); a quality with no unflagged row keeps its flagged rows where they were.
     * Every other row keeps its order.
     */
    fun demoteMayNotPlay(rows: List<StreamRow>): List<StreamRow> {
        if (rows.none { it.mayNotPlay }) return rows
        val lastClean = HashMap<StreamQuality?, Int>()
        rows.forEachIndexed { i, r -> if (!r.mayNotPlay) lastClean[r.quality] = i }
        val after = HashMap<Int, MutableList<StreamRow>>()
        val out = ArrayList<StreamRow>(rows.size)
        rows.forEachIndexed { i, r ->
            val anchor = lastClean[r.quality]
            if (r.mayNotPlay && anchor != null && anchor > i) after.getOrPut(anchor) { ArrayList() } += r else out += r
            after[i]?.let { out += it }
        }
        return out
    }

    private val REMUX = Regex("""(?i)remux""")
    private val DOLBY_VISION = Regex("""(?i)(?<![A-Za-z0-9])(dv|dovi|dolby[ ._-]?vision)(?![A-Za-z0-9])""")
    private val WEB = Regex("""(?i)(?<![A-Za-z0-9])web(?:[ ._-]?(?:dl|rip))?(?![A-Za-z0-9])""")
    private val MOVIES_ANYWHERE = Regex("""(?i)movies[ ._-]?anywhere""")
    private val SINGLE_LAYER = Regex("""(?i)(?<![A-Za-z0-9])(?:dv[ ._-]?)?(?:p|profile[ ._-]?)[58](?![A-Za-z0-9])""")
    // MEL only next to DV/DoVi: on its own it is a common name ("Mel").
    private val DUAL_LAYER = Regex("""(?i)(?<![A-Za-z0-9])(?:(?:dv[ ._-]?)?(?:p|profile[ ._-]?)7|fel|(?:dv|dovi)[ ._-]?mel)(?![A-Za-z0-9])""")

    /**
     * True when the stream looks like Dolby Vision Profile 7 (a Blu-ray REMUX with DV), which TVs without
     * a Profile 7 decoder can't show. An explicit Profile 7 (P7, FEL, MEL) counts; WEB sources, Movies
     * Anywhere hybrids and an explicit Profile 5 or 8 don't. A guess from the addon's text, never a check
     * of the file.
     */
    fun likelyDvProfile7(texts: List<String>): Boolean {
        fun has(r: Regex) = texts.any { r.containsMatchIn(it) }
        if (!has(DOLBY_VISION)) return false
        if (has(DUAL_LAYER)) return true
        if (has(SINGLE_LAYER) || has(WEB) || has(MOVIES_ANYWHERE)) return false
        return has(REMUX)
    }

    fun lines(text: String?): List<String> =
        text.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private val UHD = Regex("""(?i)\b(2160p|4k|uhd)\b""")
    private val FHD = Regex("""(?i)\b1080[pi]\b""")
    private val HD = Regex("""(?i)\b720p\b""")
    private val SD = Regex("""(?i)\b(480p|576p|360p)\b""")

    /** The best quality any of [texts] names, or null when none does. */
    fun quality(texts: List<String>): StreamQuality? = when {
        texts.any { UHD.containsMatchIn(it) } -> StreamQuality.UHD
        texts.any { FHD.containsMatchIn(it) } -> StreamQuality.FHD
        texts.any { HD.containsMatchIn(it) } -> StreamQuality.HD
        texts.any { SD.containsMatchIn(it) } -> StreamQuality.SD
        else -> null
    }

    /** Folds the repository's answer into what the screen shows. */
    fun feed(results: StreamResults): StreamFeed {
        if (results.playable.isEmpty() && results.unsupported.isEmpty() && results.errors.isNotEmpty()) {
            return StreamFeed.Failed(results.errors.first())
        }
        return StreamFeed.Loaded(rows(results.playable), results.unsupported.size, results.errors)
    }
}

/**
 * Loads the stream list for one movie or episode, asking every enabled stream addon at once. The
 * playable streams (addresses and headers included) are held here in memory only and never handed to
 * the screen, saved, or logged; the screen gets [StreamRow]s.
 */
class StreamBoard(
    private val repository: AddonRepository,
    private val registry: () -> AddonRegistry,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<StreamListState?>(null)
    val state: StateFlow<StreamListState?> = _state.asStateFlow()
    private var job: Job? = null
    private var playable: List<SourcedStream> = emptyList()

    fun open(target: StreamTarget) {
        _state.value = StreamListState(target)
        load()
    }

    fun retry() {
        val now = _state.value ?: return
        _state.value = now.copy(feed = StreamFeed.Loading)
        load()
    }

    fun close() {
        job?.cancel()
        job = null
        playable = emptyList()
        _state.value = null
    }

    /** The stream behind a row, for the player (Stage 5). Never shown or logged. */
    internal fun streamFor(key: Int): SourcedStream? = playable.getOrNull(key)

    private fun load() {
        job?.cancel()
        playable = emptyList()
        val asked = _state.value ?: return
        job = scope.launch {
            val results = repository.streams(registry(), asked.target.type, asked.target.videoId)
            val now = _state.value
            if (now == null || now.target != asked.target) return@launch
            playable = results.playable
            _state.value = now.copy(feed = StreamRows.feed(results))
        }
    }
}
