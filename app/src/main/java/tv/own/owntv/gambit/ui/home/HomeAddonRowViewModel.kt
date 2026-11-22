package tv.own.owntv.gambit.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.data.AddonStore
import tv.own.owntv.gambit.discovery.HomeRowBoard
import tv.own.owntv.gambit.discovery.HomeRowState
import tv.own.owntv.gambit.ui.discovery.DiscoveryAccess
import tv.own.owntv.gambit.ui.discovery.discoveryAccess

/**
 * Home's addon row, in the Live sports slot: a thin wrapper over [HomeRowBoard]. Nothing is requested,
 * and the row stays empty, unless the active profile is known to be an adult one.
 */
class HomeAddonRowViewModel(
    private val store: AddonStore,
    repository: AddonRepository,
    settings: SettingsRepository,
    profileDao: ProfileDao,
) : ViewModel() {

    private val board = HomeRowBoard(repository, { store.registry.value }, viewModelScope)

    private val access: StateFlow<DiscoveryAccess> = discoveryAccess(settings, profileDao)
        .stateIn(viewModelScope, SharingStarted.Eagerly, DiscoveryAccess.UNKNOWN)

    /** The row; always empty on a kids profile or before the profile is read. */
    val state: StateFlow<HomeRowState> = combine(board.state, access) { row, a ->
        if (a == DiscoveryAccess.OPEN) row else HomeRowState.EMPTY
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeRowState.EMPTY)

    init {
        // A profile switch: an adult profile asks again, any other empties the row.
        access.onEach { if (it == DiscoveryAccess.OPEN) board.refresh() else board.clear() }.launchIn(viewModelScope)
        // Addons added, removed, turned on or off, or reordered: the feeding catalogue may change.
        store.registry.drop(1).onEach { if (access.value == DiscoveryAccess.OPEN) board.refresh(force = true) }.launchIn(viewModelScope)
    }

    /** Call when Home is shown: fresh posters, the old ones stay until they arrive. */
    fun refresh() {
        if (access.value == DiscoveryAccess.OPEN) board.refresh()
    }
}
