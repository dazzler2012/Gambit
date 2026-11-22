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
    /** Display rows for the playable streams, numbered in order. Nothing from the address is kept. */
    fun rows(playable: List<SourcedStream>): List<StreamRow> = playable.mapIndexed { index, sourced ->
        val s = sourced.stream
        StreamRow(
            key = index,
            nameLines = lines(s.name),
            detailLines = lines(s.title ?: s.description),
            quality = quality(listOfNotNull(s.name, s.title, s.description, s.behaviorHints.filename)),
            sizeBytes = s.behaviorHints.videoSize?.takeIf { it > 0 },
            addonName = sourced.addonName,
        )
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
