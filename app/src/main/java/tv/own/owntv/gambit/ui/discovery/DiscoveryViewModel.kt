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

/** Whether Discovery may show for the active profile. Until the profile is read it is [UNKNOWN]. */
enum class DiscoveryAccess { UNKNOWN, KIDS, OPEN }

/**
 * Discovery: the addons' catalogues as a poster grid with a type and a catalogue/genre choice. A thin
 * wrapper over [DiscoveryBoard]. Nothing is requested until the profile is known to be an adult one.
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

    /** [DiscoveryAccess.OPEN] only once the active profile has been read and is not a kids profile. */
    val access: StateFlow<DiscoveryAccess> = settings.activeProfileId
        .flatMapLatest { id ->
            if (id < 0) {
                flowOf(DiscoveryAccess.UNKNOWN)
            } else {
                profileDao.observeById(id).map { p ->
                    when {
                        p == null -> DiscoveryAccess.UNKNOWN
                        p.isKids -> DiscoveryAccess.KIDS
                        else -> DiscoveryAccess.OPEN
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DiscoveryAccess.UNKNOWN)

    init {
        // The first value is the registry as it is now; open() reads it itself.
        store.registry.drop(1).onEach { board.onRegistryChanged() }.launchIn(viewModelScope)
    }

    /** Call when the page is shown: fetches fresh lists. Does nothing until [access] is OPEN. */
    fun open() {
        if (access.value == DiscoveryAccess.OPEN) board.open()
    }

    /** After a failed first page: forget results and ask again. */
    fun retry() = open()

    fun selectType(type: DiscoveryType) = board.selectType(type)
    fun selectCatalog(key: String) = board.selectCatalog(key)
    fun selectGenre(genre: String?) = board.selectGenre(genre)
    fun loadMore() = board.loadMore()
}
