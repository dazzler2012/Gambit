package tv.own.owntv.gambit.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.model.MetaPreview
import tv.own.owntv.gambit.addons.net.AddonResult
import tv.own.owntv.gambit.addons.net.CatalogExtraArgs

/**
 * What Home's addon row shows: the [catalog] it came from and its first [items]. Empty items hide the row.
 */
data class HomeRowState(val catalog: DiscoveryCatalog? = null, val items: List<MetaPreview> = emptyList()) {
    companion object {
        val EMPTY = HomeRowState()
    }
}

/**
 * The data behind Home's addon row (in the Live sports slot). The row is fed by the first Movies
 * catalogue in addon order, the same one Discovery opens on. If it fails or has nothing, the next Movies
 * catalogue is tried, up to [MAX_TRIES] in all; when none answers with titles the row is empty (Home then
 * hides it). Errors are never shown on Home.
 *
 * Plain Kotlin with no Android types; the view model hands it a scope. A refresh keeps the posters on
 * screen until the new answer is in. Nothing here logs or keeps an addon address.
 */
class HomeRowBoard(
    private val repository: AddonRepository,
    private val registry: () -> AddonRegistry,
    private val scope: CoroutineScope,
    /** Kids profiles: the row's titles are reported here (see [KidsTitleGate]). */
    private val kidsGate: KidsTitleGate? = null,
    /** True while the active profile is a kids one; only then are titles reported. */
    private val kidsActive: () -> Boolean = { kidsGate != null },
) {
    private val _state = MutableStateFlow(HomeRowState.EMPTY)
    val state: StateFlow<HomeRowState> = _state.asStateFlow()

    private var job: Job? = null

    /** Bumped on every refresh and clear; an answer applies only while its number is still current. */
    @Volatile private var generation = 0

    /**
     * Asks for the row again. With [force] false, a refresh already on its way is left to finish (Home
     * shown twice in a row asks once); [force] restarts it (the addon list changed).
     */
    fun refresh(force: Boolean = false) {
        if (!force && job?.isActive == true) return
        job?.cancel()
        val gen = ++generation
        val candidates = registry().browseCatalogs(DiscoveryType.MOVIE.id).take(MAX_TRIES)
        if (candidates.isEmpty()) {
            _state.value = HomeRowState.EMPTY
            return
        }
        job = scope.launch {
            for (ref in candidates) {
                val result = repository.catalog(ref)
                if (gen != generation) return@launch
                val items = (result as? AddonResult.Ok)?.value?.distinctBy { it.id }?.take(MAX_ITEMS).orEmpty()
                if (items.isNotEmpty()) {
                    if (kidsActive()) kidsGate?.report(ref.key, items.map { it.id }, CatalogExtraArgs())
                    _state.value = HomeRowState(DiscoveryCatalog(ref), items)
                    return@launch
                }
            }
            _state.value = HomeRowState.EMPTY
        }
    }

    /** Empties the row and drops any answer on its way (kids profile, or the profile not read yet). */
    fun clear() {
        job?.cancel()
        generation++
        _state.value = HomeRowState.EMPTY
    }

    companion object {
        /** How many Movies catalogues are tried before the row gives up. */
        const val MAX_TRIES = 3

        /** Posters on the row before "See all". */
        const val MAX_ITEMS = 20
    }
}
