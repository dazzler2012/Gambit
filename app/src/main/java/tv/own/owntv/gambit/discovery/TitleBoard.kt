package tv.own.owntv.gambit.discovery

import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.model.AddonVideo
import tv.own.owntv.gambit.addons.model.MetaDetail
import tv.own.owntv.gambit.addons.model.MetaPreview
import tv.own.owntv.gambit.addons.net.AddonError
import tv.own.owntv.gambit.addons.net.AddonResult
import tv.own.owntv.gambit.addons.net.AddonUrl

/** The title page while it is open: the catalogue item (shown at once) and its details as they load. */
data class TitleState(
    val preview: MetaPreview,
    /** The addon whose catalogue the item came from; asked first for details. */
    val source: AddonUrl?,
    val feed: TitleFeed = TitleFeed.Loading,
)

sealed interface TitleFeed {
    data object Loading : TitleFeed
    data class Failed(val error: AddonError) : TitleFeed

    /** [seasons] is empty for anything that isn't a series; [season] indexes it. */
    data class Loaded(val detail: MetaDetail, val seasons: List<TitleSeason>, val season: Int) : TitleFeed
}

/** One season's episodes. [number] 0 is Specials; null holds episodes with no season number. */
data class TitleSeason(val number: Int?, val episodes: List<AddonVideo>)

object TitleSeasons {
    /**
     * Episodes grouped by season: regular seasons in order, then Specials (season 0), then any episodes
     * with no season. Within a season, by episode number; episodes with none keep their order, last.
     */
    fun group(videos: List<AddonVideo>): List<TitleSeason> =
        videos.groupBy { it.season }
            .map { (number, eps) -> TitleSeason(number, eps.sortedBy { it.episode ?: Int.MAX_VALUE }) }
            .sortedWith(compareBy<TitleSeason> { rank(it.number) }.thenBy { it.number ?: 0 })

    private fun rank(number: Int?): Int = when {
        number == null -> 2
        number == 0 -> 1
        else -> 0
    }

    /** The season the page opens on: the first regular one, else the first there is. */
    fun firstSeason(seasons: List<TitleSeason>): Int =
        seasons.indexOfFirst { (it.number ?: 0) > 0 }.coerceAtLeast(0)

    /** The day an episode aired, from the addon's ISO date ("2020-01-01T00:00:00.000Z" or "2020-01-01"). */
    fun airDate(released: String?): LocalDate? {
        if (released.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(released).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(released.take(10)) }.getOrNull()
    }
}

/**
 * Loads the title page for one catalogue item. Stateless about profiles and UI: the caller decides when
 * it may open. Opening another title or closing drops any answer still on its way.
 */
class TitleBoard(
    private val repository: AddonRepository,
    private val registry: () -> AddonRegistry,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<TitleState?>(null)
    val state: StateFlow<TitleState?> = _state.asStateFlow()
    private var job: Job? = null

    fun open(preview: MetaPreview, source: AddonUrl?) {
        _state.value = TitleState(preview, source)
        load()
    }

    fun retry() {
        val now = _state.value ?: return
        _state.value = now.copy(feed = TitleFeed.Loading)
        load()
    }

    fun close() {
        job?.cancel()
        job = null
        _state.value = null
    }

    fun selectSeason(index: Int) {
        val now = _state.value ?: return
        val feed = now.feed as? TitleFeed.Loaded ?: return
        if (index !in feed.seasons.indices) return
        _state.value = now.copy(feed = feed.copy(season = index))
    }

    private fun load() {
        job?.cancel()
        val asked = _state.value ?: return
        job = scope.launch {
            val result = repository.meta(registry(), asked.preview.type, asked.preview.id, asked.source)
            val now = _state.value
            if (now == null || now.preview.id != asked.preview.id) return@launch
            _state.value = now.copy(
                feed = when (result) {
                    is AddonResult.Ok -> {
                        val seasons = if (asked.preview.type == DiscoveryType.SERIES.id) TitleSeasons.group(result.value.videos) else emptyList()
                        TitleFeed.Loaded(result.value, seasons, TitleSeasons.firstSeason(seasons))
                    }
                    is AddonResult.Err -> TitleFeed.Failed(result.error)
                },
            )
        }
    }
}
