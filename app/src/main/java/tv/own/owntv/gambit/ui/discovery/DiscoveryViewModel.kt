package tv.own.owntv.gambit.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.data.AddonStore
import tv.own.owntv.gambit.discovery.DiscoveryBoard
import tv.own.owntv.gambit.discovery.DiscoveryState
import tv.own.owntv.gambit.discovery.DiscoveryType

/**
 * Discovery: the addons' catalogues as a poster grid with a type and a catalogue/genre choice. A thin
 * wrapper over [DiscoveryBoard]. Nothing is requested on a kids profile. No screen uses it yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewModel(
    private val store: AddonStore,
    repository: AddonRepository,
    settings: SettingsRepository,
    profileDao: ProfileDao,
) : ViewModel() {

    private val board = DiscoveryBoard(repository, { store.registry.value }, viewModelScope)

    val state: StateFlow<DiscoveryState> = board.state

    /** True on a kids profile: Discovery is hidden there and never loads. */
    val kids: StateFlow<Boolean> = settings.activeProfileId
        .flatMapLatest { id -> if (id < 0) flowOf(null) else profileDao.observeById(id) }
        .map { it?.isKids == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        // The first value is the registry as it is now; open() reads it itself.
        store.registry.drop(1).onEach { board.onRegistryChanged() }.launchIn(viewModelScope)
    }

    /** Call when the page is shown: fetches fresh lists. */
    fun open() {
        if (!kids.value) board.open()
    }

    fun selectType(type: DiscoveryType) = board.selectType(type)
    fun selectCatalog(key: String) = board.selectCatalog(key)
    fun selectGenre(genre: String?) = board.selectGenre(genre)
    fun loadMore() = board.loadMore()
}
