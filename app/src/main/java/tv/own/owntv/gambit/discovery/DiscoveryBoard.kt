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
import tv.own.owntv.gambit.addons.data.CatalogRef
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
    /** Kids profiles: every page of titles is reported here (see [KidsTitleGate]). */
    private val kidsGate: KidsTitleGate? = null,
    /** True while the active profile is a kids one: default view plus paging only, no genres. */
    private val kidsActive: () -> Boolean = { kidsGate != null },
) {
    private val kids: Boolean get() = kidsGate != null && kidsActive()

    private fun wrap(refs: List<CatalogRef>): List<DiscoveryCatalog> = refs.map { DiscoveryCatalog(it, genresShown = !kids) }

    private val _state = MutableStateFlow(DiscoveryState())
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private data class CacheKey(val catalogKey: String, val genre: String?)

    private val cache = mutableMapOf<CacheKey, DiscoveryFeed>()
    private var job: Job? = null

    /** Bumped on every new request; a reply applies only while its number is still current. */
    @Volatile private var generation = 0
    private var opened = false

    /**
     * Call when Discovery is shown: forgets earlier results and loads the chosen catalogue again. [type]
     * and [catalogKey] choose what it opens on (Home's row asks for its own catalogue); a key no enabled
     * addon offers any more is ignored and the usual choice stays.
     */
    fun open(type: DiscoveryType? = null, catalogKey: String? = null) {
        opened = true
        cache.clear()
        if (type != null && type != _state.value.type) {
            _state.update { it.copy(type = type, selected = null, genre = null) }
        }
        if (catalogKey != null && catalogKey != _state.value.selected?.key) {
            val wanted = wrap(registry().browseCatalogs(_state.value.type.id)).firstOrNull { it.key == catalogKey }
            if (wanted != null) _state.update { it.copy(selected = wanted, genre = null) }
        }
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
        if (kids) return // Gambit: kids get the default view only
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
        val genre = if (kids) null else s.genre
        val extras = CatalogExtraArgs(genre = genre, skip = feed.fetched)
        val gen = ++generation
        job?.cancel()
        _state.update { it.copy(feed = feed.copy(loadingMore = true, moreError = null)) }
        job = scope.launch {
            val result = repository.catalog(catalog.ref, extras)
            if (gen != generation) return@launch
            if (kids && result is AddonResult.Ok) kidsGate?.report(catalog.key, result.value.map { it.id }, extras)
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
        val catalogs = wrap(registry().browseCatalogs(type.id))
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
            val extras = CatalogExtraArgs(genre = if (kids) null else s.genre)
            val result = repository.catalog(catalog.ref, extras)
            if (gen != generation) return@launch
            if (kids && result is AddonResult.Ok) kidsGate?.report(catalog.key, result.value.map { it.id }, extras)
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
