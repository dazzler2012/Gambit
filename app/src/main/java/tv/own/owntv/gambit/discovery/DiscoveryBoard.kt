package tv.own.owntv.gambit.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.model.MetaPreview
import tv.own.owntv.gambit.addons.net.AddonResult
import tv.own.owntv.gambit.addons.net.CatalogExtraArgs

/**
 * The data behind the Discovery page: which catalogue and genre are chosen, and the titles loaded for it.
 * Plain Kotlin with no Android types; the view model hands it a scope.
 *
 * Only the chosen catalogue is ever requested, one page at a time, so a long addon list costs nothing
 * until it is picked. Results stay in memory for the session; [open] clears them, so showing the page
 * again fetches fresh lists. A change of choice cancels the request in flight, and a late reply for an
 * old choice is dropped. Nothing here logs or keeps an addon address.
 *
 * Calls come from one thread (the main one in the app).
 */
class DiscoveryBoard(
    private val repository: AddonRepository,
    private val registry: () -> AddonRegistry,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DiscoveryState())
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private data class CacheKey(val catalogKey: String, val genre: String?)

    private val cache = mutableMapOf<CacheKey, DiscoveryFeed>()
    private var job: Job? = null

    /** Bumped on every new request; a reply applies only while its number is still current. */
    @Volatile private var generation = 0
    private var opened = false

    /** Call when Discovery is shown: forgets earlier results and loads the chosen catalogue again. */
    fun open() {
        opened = true
        cache.clear()
        syncCatalogs(forceReload = true)
    }

    /** Call when the enabled addons changed (the registry flow emitted). Does nothing before [open]. */
    fun onRegistryChanged() {
        if (opened) syncCatalogs(forceReload = false)
    }

    fun selectType(type: DiscoveryType) {
        if (type == _state.value.type) return
        _state.update { it.copy(type = type, selected = null, genre = null) }
        syncCatalogs(forceReload = true)
    }

    fun selectCatalog(key: String) {
        val catalog = _state.value.catalogs.firstOrNull { it.key == key } ?: return
        if (catalog.key == _state.value.selected?.key) return
        _state.update { it.copy(selected = catalog, genre = null) }
        load()
    }

    /** [genre] must be one of the selected catalogue's genres, or null for all. */
    fun selectGenre(genre: String?) {
        val selected = _state.value.selected ?: return
        if (genre != null && genre !in selected.genres) return
        if (genre == _state.value.genre) return
        _state.update { it.copy(genre = genre) }
        load()
    }

    /** Asks for the next page of the chosen catalogue. Does nothing while loading or when there is no more. */
    fun loadMore() {
        val s = _state.value
        val catalog = s.selected ?: return
        val feed = s.feed as? DiscoveryFeed.Loaded ?: return
        if (!feed.canLoadMore || feed.loadingMore) return
        val genre = s.genre
        val gen = ++generation
        job?.cancel()
        _state.update { it.copy(feed = feed.copy(loadingMore = true, moreError = null)) }
        job = scope.launch {
            val result = repository.catalog(catalog.ref, CatalogExtraArgs(genre = genre, skip = feed.fetched))
            if (gen != generation) return@launch
            val next = when (result) {
                is AddonResult.Err -> feed.copy(loadingMore = false, moreError = result.error)
                is AddonResult.Ok -> {
                    val seen = feed.items.mapTo(HashSet()) { it.id }
                    val fresh = result.value.filter { seen.add(it.id) }
                    // A page with nothing new ends paging: an addon that ignores `skip` would repeat forever.
                    feed.copy(
                        items = feed.items + fresh,
                        fetched = feed.fetched + result.value.size,
                        canLoadMore = fresh.isNotEmpty(),
                        loadingMore = false,
                    )
                }
            }
            cache[CacheKey(catalog.key, genre)] = next
            _state.update { it.copy(feed = next) }
        }
    }

    /** Rebuilds the catalogue list for the chosen type; keeps the choice when it still exists. */
    private fun syncCatalogs(forceReload: Boolean) {
        val type = _state.value.type
        val catalogs = registry().browseCatalogs(type.id).map(::DiscoveryCatalog)
        val current = _state.value.selected
        val kept = current?.let { c -> catalogs.firstOrNull { it.key == c.key } }
        val selected = kept ?: catalogs.firstOrNull()
        val genre = if (kept != null) _state.value.genre?.takeIf { it in kept.genres } else null
        val unchanged = kept != null && genre == _state.value.genre && catalogs == _state.value.catalogs
        _state.update { it.copy(catalogs = catalogs, selected = selected, genre = genre) }
        if (forceReload || !unchanged || selected?.key != current?.key) load()
    }

    private fun load() {
        job?.cancel()
        val gen = ++generation
        val s = _state.value
        val catalog = s.selected
        if (catalog == null) {
            _state.update { it.copy(feed = DiscoveryFeed.Empty) }
            return
        }
        val key = CacheKey(catalog.key, s.genre)
        cache[key]?.let { cached ->
            _state.update { it.copy(feed = cached) }
            return
        }
        _state.update { it.copy(feed = DiscoveryFeed.Loading) }
        job = scope.launch {
            val result = repository.catalog(catalog.ref, CatalogExtraArgs(genre = s.genre))
            if (gen != generation) return@launch
            val feed = when (result) {
                is AddonResult.Err -> DiscoveryFeed.Failed(result.error)
                is AddonResult.Ok -> firstPage(result.value, catalog.canPage)
            }
            // A failure isn't cached: choosing the catalogue again tries again.
            if (feed !is DiscoveryFeed.Failed) cache[key] = feed
            _state.update { it.copy(feed = feed) }
        }
    }

    private fun firstPage(metas: List<MetaPreview>, canPage: Boolean): DiscoveryFeed {
        val items = metas.distinctBy { it.id }
        if (items.isEmpty()) return DiscoveryFeed.Empty
        return DiscoveryFeed.Loaded(items = items, fetched = metas.size, canLoadMore = canPage)
    }
}
