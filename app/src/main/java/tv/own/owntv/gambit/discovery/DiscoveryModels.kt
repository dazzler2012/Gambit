package tv.own.owntv.gambit.discovery

import tv.own.owntv.gambit.addons.data.CatalogRef
import tv.own.owntv.gambit.addons.model.AddonCatalog
import tv.own.owntv.gambit.addons.model.MetaPreview
import tv.own.owntv.gambit.addons.net.AddonError

/** The first drop-down of Discovery. [id] is the type string the addons use (`movie`, `series`, or any custom one). */
data class DiscoveryType(val id: String) {
    /** "anime.movie" → "Anime Movie", "trakt" → "Trakt". For types without their own translated name. */
    val readableName: String
        get() = buildString {
            id.split('.', '_', '-', ' ').filter { it.isNotBlank() }.forEach { w ->
                if (isNotEmpty()) append(' ')
                append(w.replaceFirstChar { it.uppercaseChar() })
            }
        }.ifBlank { id }

    companion object {
        val MOVIE = DiscoveryType("movie")
        val SERIES = DiscoveryType("series")
    }
}

/**
 * One entry of the second drop-down: a catalogue of an addon. [title] is the manifest's name for it (may
 * be null, the screen then falls back); [genres] are the genre values the addon offers, if any.
 * Holds no address: [CatalogRef] reaches the addon only through its redacted `toString`.
 */
data class DiscoveryCatalog(val ref: CatalogRef, val genresShown: Boolean = true /* Gambit: false for kids */) {
    val key: String get() = ref.key
    val title: String? get() = ref.catalog.name?.takeIf { it.isNotBlank() }
    val addonName: String get() = ref.addon.manifest.name
    val genres: List<String> get() = if (genresShown) ref.catalog.genreOptions() else emptyList()

    /** True when the addon can't answer without a genre: there is no "All", and the first genre is the default. */
    val genreRequired: Boolean get() = genresShown && ref.catalog.extra.any { it.name == "genre" && it.isRequired }

    /** True when the addon says it can page this catalogue with `skip`. */
    val canPage: Boolean get() = ref.catalog.supports("skip")
}

private fun AddonCatalog.genreOptions(): List<String> =
    extra.firstOrNull { it.name == "genre" }?.options.orEmpty().filter { it.isNotBlank() }.distinct()

/** What the poster grid shows. Failures are typed; the screen words them, nothing here holds text. */
sealed interface DiscoveryFeed {
    /** Nothing chosen yet, or Discovery is closed (kids profile). */
    data object Idle : DiscoveryFeed

    data object Loading : DiscoveryFeed

    /** The addon answered with no titles. */
    data object Empty : DiscoveryFeed

    data class Failed(val error: AddonError) : DiscoveryFeed

    /**
     * [items] are in addon order with no repeated id. [fetched] is how many titles the addon has sent
     * so far, which is what the next `skip` is (it can exceed [items] when an addon repeats titles).
     * [moreError] is set when the last "load more" failed; the items stay and asking again retries.
     */
    data class Loaded(
        val items: List<MetaPreview>,
        val fetched: Int,
        val canLoadMore: Boolean,
        val loadingMore: Boolean = false,
        val moreError: AddonError? = null,
    ) : DiscoveryFeed
}

/** Everything the Discovery page needs. */
data class DiscoveryState(
    val type: DiscoveryType = DiscoveryType.MOVIE,
    /** The first drop-down's choices: Movies, Series, then any other type the addons offer. */
    val types: List<DiscoveryType> = listOf(DiscoveryType.MOVIE, DiscoveryType.SERIES),
    /** The second drop-down for [type], in addon order. Empty when no enabled addon has one. */
    val catalogs: List<DiscoveryCatalog> = emptyList(),
    val selected: DiscoveryCatalog? = null,
    /** The chosen genre of [selected], or null for all. */
    val genre: String? = null,
    val feed: DiscoveryFeed = DiscoveryFeed.Idle,
)
