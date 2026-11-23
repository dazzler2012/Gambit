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
import tv.own.owntv.gambit.addons.data.KidsAddonAccess
import tv.own.owntv.gambit.discovery.HomeRowBoard
import tv.own.owntv.gambit.discovery.HomeRowState
import tv.own.owntv.gambit.discovery.KidsTitleGate
import tv.own.owntv.gambit.ui.discovery.AddonAccess
import tv.own.owntv.gambit.ui.discovery.isOpen
import tv.own.owntv.gambit.ui.discovery.observeAddonAccess
import tv.own.owntv.gambit.ui.discovery.registryFrom

/**
 * Home's addon row, in the Live sports slot: a thin wrapper over [HomeRowBoard]. Nothing is requested,
 * and the row stays empty, unless the active profile is an adult one, or a kids one with approved
 * catalogues (then the row comes from the first approved Movies catalogue, and is hidden if there is none).
 */
class HomeAddonRowViewModel(
    private val store: AddonStore,
    repository: AddonRepository,
    settings: SettingsRepository,
    profileDao: ProfileDao,
    kidsAccess: KidsAddonAccess,
    private val gate: KidsTitleGate,
) : ViewModel() {

    private val access: StateFlow<AddonAccess> = observeAddonAccess(settings, profileDao, kidsAccess)
        .stateIn(viewModelScope, SharingStarted.Eagerly, AddonAccess.Unknown)

    private val board = HomeRowBoard(repository, { access.value.registryFrom(store.registry.value) }, viewModelScope, gate) { access.value is AddonAccess.Kids }

    /** The row; always empty on a closed kids profile or before the profile is read. */
    val state: StateFlow<HomeRowState> = combine(board.state, access) { row, a ->
        if (a.isOpen) row else HomeRowState.EMPTY
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeRowState.EMPTY)

    init {
        // A profile switch (or new approvals): an open profile asks again, any other empties the row.
        access.onEach { a ->
            when (a) {
                AddonAccess.Adult -> { gate.reset(); board.refresh() }
                is AddonAccess.Kids -> { gate.retainApproved(a.profileId, a.approved); board.refresh(force = true) }
                else -> { gate.reset(); board.clear() }
            }
        }.launchIn(viewModelScope)
        // Addons added, removed, turned on or off, or reordered: the feeding catalogue may change.
        store.registry.drop(1).onEach { if (access.value.isOpen) board.refresh(force = true) }.launchIn(viewModelScope)
    }

    /** Call when Home is shown: fresh posters, the old ones stay until they arrive. */
    fun refresh() {
        if (access.value.isOpen) board.refresh()
    }
}
