package tv.own.owntv.gambit.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.own.owntv.gambit.playback.AddonPlayRequest
import tv.own.owntv.gambit.playback.AddonPlayResult
import tv.own.owntv.gambit.playback.GambitAddonPlayer
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
    private val addonPlayer: GambitAddonPlayer,
) : ViewModel() {

    private val board = DiscoveryBoard(repository, { store.registry.value }, viewModelScope)

    val state: StateFlow<DiscoveryState> = board.state

    private val titles = TitleBoard(repository, { store.registry.value }, viewModelScope)

    /** The title page, while one is open over Discovery; null shows the grid. */
    val title: StateFlow<TitleState?> = titles.state

    private val streamBoard = StreamBoard(repository, { store.registry.value }, viewModelScope)

    /** The stream list, while one is open over the title page; null shows the title page. */
    val streams: StateFlow<StreamListState?> = streamBoard.state

    private val _playNote = MutableStateFlow<PlayNote?>(null)

    /** A line on the stream list about the last OK: starting, or why it can't play. */
    val playNote: StateFlow<PlayNote?> = _playNote.asStateFlow()

    /** The stream row last sent to the player; focus returns to it after Back from the player. */
    var lastPlayedKey: Int? = null
        private set

    // Set when a stream starts: the page is rebuilt after the player closes, and must come back as it was.
    private var returningFromPlayer = false
    private var playJob: Job? = null

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
        if (returningFromPlayer) {
            returningFromPlayer = false
            if (streamBoard.state.value != null) return
        }
        lastPlayedKey = null
        _playNote.value = null
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
        val id = detail?.id ?: open.preview.id
        _playNote.value = null
        lastPlayedKey = null
        streamBoard.open(StreamTarget(open.preview.type, id, name, metaId = id, year = detail?.releaseInfo ?: open.preview.releaseInfo))
    }

    /** OK on an episode: streams for that episode's video id. */
    fun findEpisodeStreams(video: AddonVideo) {
        val open = title.value ?: return
        if (access.value != DiscoveryAccess.OPEN) return
        val detail = (open.feed as? TitleFeed.Loaded)?.detail
        val name = detail?.name?.takeIf { it.isNotBlank() } ?: open.preview.name.orEmpty()
        _playNote.value = null
        lastPlayedKey = null
        streamBoard.open(
            StreamTarget(
                open.preview.type, video.id, name, video.season, video.episode, video.title?.takeIf { it.isNotBlank() },
                metaId = detail?.id ?: open.preview.id,
            ),
        )
    }

    /**
     * OK on a stream row: plays it through the localhost proxy, then [onStarted] opens the full-screen
     * player. Refused on a kids profile (or before the profile is known) and for HLS.
     */
    fun playStream(key: Int, onStarted: () -> Unit) {
        if (!canPlay(access.value)) return
        val target = streamBoard.state.value?.target ?: return
        val sourced = streamBoard.streamFor(key) ?: return
        playJob?.cancel()
        _playNote.value = PlayNote.STARTING
        playJob = viewModelScope.launch {
            val request = AddonPlayRequest(
                stream = sourced.stream,
                addonName = sourced.addonName,
                type = target.type,
                videoId = target.videoId,
                metaId = target.metaId,
                title = target.title,
                year = target.year,
                season = target.season,
                episode = target.episode,
                episodeTitle = target.episodeTitle,
            )
            when (addonPlayer.play(request)) {
                AddonPlayResult.STARTED -> {
                    _playNote.value = null
                    lastPlayedKey = key
                    returningFromPlayer = true
                    onStarted()
                }
                AddonPlayResult.HLS -> _playNote.value = PlayNote.HLS
                AddonPlayResult.NOT_PLAYABLE -> _playNote.value = PlayNote.NOT_PLAYABLE
            }
        }
    }

    fun closeStreams() {
        playJob?.cancel()
        _playNote.value = null
        lastPlayedKey = null
        streamBoard.close()
    }
    fun retryStreams() = streamBoard.retry()
}

/** What the stream list says about the last stream picked. */
enum class PlayNote { STARTING, HLS, NOT_PLAYABLE }

/** Only an adult profile that has been read may play an addon stream. */
internal fun canPlay(access: DiscoveryAccess): Boolean = access == DiscoveryAccess.OPEN
