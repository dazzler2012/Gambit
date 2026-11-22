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
import tv.own.owntv.gambit.discovery.StreamBoard
import tv.own.owntv.gambit.discovery.StreamListState
import tv.own.owntv.gambit.discovery.StreamTarget
import tv.own.owntv.gambit.discovery.TitleBoard
import tv.own.owntv.gambit.discovery.TitleFeed
import tv.own.owntv.gambit.discovery.TitleState
import tv.own.owntv.gambit.addons.model.AddonVideo
import tv.own.owntv.gambit.addons.model.MetaPreview

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

    private val titles = TitleBoard(repository, { store.registry.value }, viewModelScope)

    /** The title page, while one is open over Discovery; null shows the grid. */
    val title: StateFlow<TitleState?> = titles.state

    private val streamBoard = StreamBoard(repository, { store.registry.value }, viewModelScope)

    /** The stream list, while one is open over the title page; null shows the title page. */
    val streams: StateFlow<StreamListState?> = streamBoard.state

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
        // A kids profile (or one not read yet) never keeps a title page open.
        access.onEach {
            if (it != DiscoveryAccess.OPEN) {
                streamBoard.close()
                titles.close()
            }
        }.launchIn(viewModelScope)
    }

    /** Call when the page is shown: fetches fresh lists. Does nothing until [access] is OPEN. */
    fun open() {
        streamBoard.close()
        titles.close()
        if (access.value == DiscoveryAccess.OPEN) board.open()
    }

    /** After a failed first page: forget results and ask again. */
    fun retry() = open()

    fun selectType(type: DiscoveryType) = board.selectType(type)
    fun selectCatalog(key: String) = board.selectCatalog(key)
    fun selectGenre(genre: String?) = board.selectGenre(genre)
    fun loadMore() = board.loadMore()

    /** OK on a poster: its details, asked first from the addon whose catalogue is showing. */
    fun openTitle(preview: MetaPreview) {
        if (access.value != DiscoveryAccess.OPEN) return
        streamBoard.close()
        titles.open(preview, state.value.selected?.ref?.addon?.url)
    }

    fun closeTitle() {
        streamBoard.close()
        titles.close()
    }
    fun retryTitle() = titles.retry()
    fun selectSeason(index: Int) = titles.selectSeason(index)

    /** "Find streams" on a movie: streams for the title's own id. */
    fun findStreams() {
        val open = title.value ?: return
        if (access.value != DiscoveryAccess.OPEN) return
        val detail = (open.feed as? TitleFeed.Loaded)?.detail
        val name = detail?.name?.takeIf { it.isNotBlank() } ?: open.preview.name.orEmpty()
        streamBoard.open(StreamTarget(open.preview.type, detail?.id ?: open.preview.id, name))
    }

    /** OK on an episode: streams for that episode's video id. */
    fun findEpisodeStreams(video: AddonVideo) {
        val open = title.value ?: return
        if (access.value != DiscoveryAccess.OPEN) return
        val detail = (open.feed as? TitleFeed.Loaded)?.detail
        val name = detail?.name?.takeIf { it.isNotBlank() } ?: open.preview.name.orEmpty()
        streamBoard.open(StreamTarget(open.preview.type, video.id, name, video.season, video.episode, video.title?.takeIf { it.isNotBlank() }))
    }

    fun closeStreams() = streamBoard.close()
    fun retryStreams() = streamBoard.retry()
}
