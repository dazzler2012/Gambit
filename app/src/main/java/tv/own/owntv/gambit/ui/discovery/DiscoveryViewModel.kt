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
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.flow.combine
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.data.AddonStore
import tv.own.owntv.gambit.addons.data.KidsAccessRelease
import tv.own.owntv.gambit.addons.data.KidsAddonAccess
import tv.own.owntv.gambit.discovery.KidsTitleGate
import tv.own.owntv.gambit.discovery.KidsTitleSession
import tv.own.owntv.gambit.discovery.DiscoveryBoard
import tv.own.owntv.gambit.discovery.DiscoveryCatalog
import tv.own.owntv.gambit.discovery.DiscoveryState
import tv.own.owntv.gambit.discovery.DiscoveryType
import tv.own.owntv.gambit.discovery.StreamBoard
import tv.own.owntv.gambit.discovery.StreamListState
import tv.own.owntv.gambit.discovery.StreamTarget
import tv.own.owntv.gambit.discovery.TitleBoard
import tv.own.owntv.gambit.discovery.TitleFeed
import tv.own.owntv.gambit.discovery.TitleState
import tv.own.owntv.gambit.discovery.TraktDiscovery
import tv.own.owntv.gambit.discovery.TraktDiscoveryState
import tv.own.owntv.gambit.discovery.TraktListSource
import tv.own.owntv.gambit.discovery.TraktListState
import tv.own.owntv.gambit.trakt.GambitTrakt
import tv.own.owntv.gambit.addons.model.AddonVideo
import tv.own.owntv.gambit.addons.model.MetaPreview

/**
 * Whether Discovery may show for the active profile. Until the profile is read it is [UNKNOWN]. Always
 * derived from [AddonAccess] by [toDiscoveryAccess]; [OPEN] means an adult profile and nothing else.
 */
enum class DiscoveryAccess { UNKNOWN, KIDS, OPEN }

/**
 * What the active profile may do with addon content; the single source of truth ([DiscoveryAccess] is
 * derived from it).
 *  - [Unknown]: the profile has not been read yet; nothing is requested.
 *  - [Adult]: a profile that is not Kids; unchanged.
 *  - [Closed]: a kids profile with no approvals, or any kids profile while the release lock is on.
 *  - [Kids]: a kids profile with approved catalogues, only while the lock allows it. [profileId] tells
 *    the title gate when the profile changed.
 */
sealed interface AddonAccess {
    data object Unknown : AddonAccess
    data object Closed : AddonAccess
    data object Adult : AddonAccess
    data class Kids(val approved: Set<String>, val profileId: Long = -1L) : AddonAccess
}

/** [isKids] is null until the profile has been read. */
internal fun addonAccessOf(
    isKids: Boolean?,
    approved: Set<String>,
    allowKids: Boolean = KidsAccessRelease.ALLOWED,
    profileId: Long = -1L,
): AddonAccess = when {
    isKids == null -> AddonAccess.Unknown
    !isKids -> AddonAccess.Adult
    allowKids && approved.isNotEmpty() -> AddonAccess.Kids(approved, profileId)
    else -> AddonAccess.Closed
}

/** The one mapping to [DiscoveryAccess]. Kids never maps to [DiscoveryAccess.OPEN]. */
internal fun AddonAccess.toDiscoveryAccess(): DiscoveryAccess = when (this) {
    AddonAccess.Adult -> DiscoveryAccess.OPEN
    AddonAccess.Unknown -> DiscoveryAccess.UNKNOWN
    AddonAccess.Closed -> DiscoveryAccess.KIDS
    is AddonAccess.Kids -> DiscoveryAccess.KIDS
}

/** True when addon content may be requested at all: an adult profile, or a kids profile with approvals. */
internal val AddonAccess.isOpen: Boolean get() = this is AddonAccess.Adult || this is AddonAccess.Kids

/**
 * The registry this access may use: all of it for an adult, only the approved browse catalogues for kids,
 * and nothing while closed or unknown.
 */
internal fun AddonAccess.registryFrom(all: AddonRegistry): AddonRegistry = when (this) {
    AddonAccess.Adult -> all
    is AddonAccess.Kids -> all.limitedTo(approved)
    else -> AddonRegistry()
}

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
    kidsAccess: KidsAddonAccess,
    private val gate: KidsTitleGate,
    gambitTrakt: GambitTrakt,
) : ViewModel() {

    /** What the active profile may do with addons; see [AddonAccess]. */
    val addonAccess: StateFlow<AddonAccess> = observeAddonAccess(settings, profileDao, kidsAccess)
        .stateIn(viewModelScope, SharingStarted.Eagerly, AddonAccess.Unknown)

    private val traktConfigured = gambitTrakt.isConfigured

    /** Trakt in Discovery: adult profiles only, in a build with a Client ID. */
    private val traktDiscovery = TraktDiscovery(gambitTrakt.client, viewModelScope, { traktAllowed(addonAccess.value, traktConfigured) })

    /** Whether the type drop-down offers "Trakt". */
    val traktShown: StateFlow<Boolean> = addonAccess.map { traktAllowed(it, traktConfigured) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Trakt's sign-in, list drop-down and choice; [TraktDiscoveryState.active] while "Trakt" is the type. */
    val trakt: StateFlow<TraktDiscoveryState> = traktDiscovery.state

    /** The titles of the chosen Trakt list. */
    val traktList: StateFlow<TraktListState> = traktDiscovery.list

    private val kidsTitle = KidsTitleSession(gate)

    private fun registryNow(): AddonRegistry = addonAccess.value.registryFrom(store.registry.value)

    private val board = DiscoveryBoard(repository, { registryNow() }, viewModelScope, gate, { addonAccess.value is AddonAccess.Kids })

    val state: StateFlow<DiscoveryState> = board.state

    private val titles = TitleBoard(repository, { registryNow() }, viewModelScope)

    /** The title page, while one is open over Discovery; null shows the grid. */
    val title: StateFlow<TitleState?> = titles.state

    private val streamBoard = StreamBoard(repository, { registryNow() }, viewModelScope)

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
    val access: StateFlow<DiscoveryAccess> = addonAccess.map { it.toDiscoveryAccess() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DiscoveryAccess.UNKNOWN)

    /** What Home's addon row asked Discovery to open on; taken by the next [open]. */
    private class HomeRequest(val type: DiscoveryType?, val catalogKey: String)
    private var fromHome: HomeRequest? = null

    init {
        // The first value is the registry as it is now; open() reads it itself.
        store.registry.drop(1).onEach { board.onRegistryChanged() }.launchIn(viewModelScope)
        // A play Gambit had to stop (stuck on both engines): say so on the stream list.
        addonPlayer.failures.onEach { _playNote.value = PlayNote.FAILED }.launchIn(viewModelScope)
        // Trakt: forgotten on a profile switch, on sign-out, and whenever access stops being adult.
        var shownProfile: Any? = null
        settings.activeProfileId.onEach { id ->
            if (shownProfile != null && id != shownProfile) traktDiscovery.forget()
            shownProfile = id
        }.launchIn(viewModelScope)
        gambitTrakt.signOuts.drop(1).onEach { traktDiscovery.signedOut() }.launchIn(viewModelScope)
        // A closed profile (or one not read yet) never keeps a title page open; a kids profile keeps one only
        // while its catalogue is still approved.
        addonAccess.onEach { a ->
            traktDiscovery.accessChanged()
            when (a) {
                AddonAccess.Adult -> gate.reset()
                is AddonAccess.Kids -> {
                    gate.retainApproved(a.profileId, a.approved)
                    board.onRegistryChanged()
                    if (titles.state.value != null && !kidsTitle.mayStream()) {
                        kidsTitle.close()
                        streamBoard.close()
                        titles.close()
                    }
                }
                else -> {
                    gate.reset()
                    kidsTitle.close()
                    streamBoard.close()
                    titles.close()
                }
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
        val request = fromHome
        fromHome = null
        // A title picked on Home is already open (see openFromHome); everything else starts on the grid.
        if (request == null) {
            kidsTitle.close()
            streamBoard.close()
            titles.close()
        }
        if (addonAccess.value.isOpen) board.open(request?.type, request?.catalogKey)
        // Home's row opens an addon catalogue; otherwise a Trakt list on screen is checked again.
        if (request != null) traktDiscovery.leave() else traktDiscovery.refresh()
    }

    /**
     * Home's addon row, before the shell shows Discovery: open on [catalog], and with [preview] open its
     * title page at once (the same page a poster in Discovery opens; Back then shows that catalogue's grid).
     * "See all" passes no preview. Does nothing unless the profile is an adult one.
     */
    fun openFromHome(catalog: DiscoveryCatalog, preview: MetaPreview? = null) {
        if (!mayOpen(catalog.key, preview?.id)) return
        playJob?.cancel()
        returningFromPlayer = false
        streamBoard.close()
        titles.close()
        if (preview != null) titles.open(preview, catalog.ref.addon.url)
        fromHome = HomeRequest(DiscoveryType(catalog.ref.catalog.type), catalog.key)
    }

    /** After a failed first page: forget results and ask again. */
    fun retry() = open()

    /** "Trakt" goes to Trakt (refused unless allowed); any other type leaves it for the addons' catalogues. */
    fun selectType(type: DiscoveryType) {
        if (type == DiscoveryType.TRAKT) {
            traktDiscovery.enter()
            return
        }
        traktDiscovery.leave()
        board.selectType(type)
    }

    fun selectTraktList(source: TraktListSource) = traktDiscovery.select(source)
    fun retryTrakt() = traktDiscovery.retry()
    fun retryTraktList() = traktDiscovery.retryList()
    fun selectCatalog(key: String) = board.selectCatalog(key)
    fun selectGenre(genre: String?) = board.selectGenre(genre)
    fun loadMore() = board.loadMore()

    /** OK on a poster: its details, asked first from the addon whose catalogue is showing. */
    fun openTitle(preview: MetaPreview) {
        // A Trakt title: adult profiles only; details come from whichever addon serves its id.
        if (trakt.value.active) {
            if (addonAccess.value != AddonAccess.Adult) return
            kidsTitle.close()
            streamBoard.close()
            titles.open(preview, null)
            return
        }
        val catalogKey = state.value.selected?.key
        if (catalogKey == null && addonAccess.value is AddonAccess.Kids) return
        if (!mayOpen(catalogKey.orEmpty(), preview.id)) return
        streamBoard.close()
        titles.open(preview, state.value.selected?.ref?.addon?.url)
    }

    fun closeTitle() {
        kidsTitle.close()
        streamBoard.close()
        titles.close()
    }
    fun retryTitle() = titles.retry()
    fun selectSeason(index: Int) = titles.selectSeason(index)

    /** "Find streams" on a movie: streams for the title's own id. */
    fun findStreams() {
        val open = title.value ?: return
        if (!mayStream()) return
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
        if (!mayStream()) return
        val detail = (open.feed as? TitleFeed.Loaded)?.detail
        // Kids: only an episode of the approved series whose page is open.
        if (addonAccess.value is AddonAccess.Kids && !kidsTitle.mayStreamEpisode(detail?.videos?.map { it.id }, video.id)) return
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
        // Kids never pass canPlay (it is for OPEN only); they play only from the stream list of a gated title.
        if (!canPlay(access.value) && !(addonAccess.value is AddonAccess.Kids && kidsTitle.mayStream())) return
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

    /** May a title page open? Adults: yes. Kids: only a title the gate has seen in an approved catalogue. */
    private fun mayOpen(catalogKey: String, id: String?): Boolean = when (val a = addonAccess.value) {
        AddonAccess.Adult -> { kidsTitle.close(); true }
        is AddonAccess.Kids -> when {
            catalogKey !in a.approved -> false
            id == null -> { kidsTitle.close(); true }
            else -> kidsTitle.open(catalogKey, id)
        }
        else -> false
    }

    /** May streams be listed for the open title page? Kids: only while it is a gated title. */
    private fun mayStream(): Boolean = when (addonAccess.value) {
        AddonAccess.Adult -> true
        is AddonAccess.Kids -> kidsTitle.mayStream()
        else -> false
    }

    fun closeStreams() {
        playJob?.cancel()
        _playNote.value = null
        lastPlayedKey = null
        streamBoard.close()
    }
    fun retryStreams() = streamBoard.retry()
}

/**
 * The active profile's [AddonAccess], as it changes. Shared by Discovery and Home's addon row. A kids
 * profile counts as kids only while it is a kids profile right now, whatever is stored for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeAddonAccess(
    settings: SettingsRepository,
    profileDao: ProfileDao,
    kids: KidsAddonAccess,
    allowKids: Boolean = KidsAccessRelease.ALLOWED,
): Flow<AddonAccess> =
    settings.activeProfileId.flatMapLatest { id ->
        if (id < 0) {
            flowOf(AddonAccess.Unknown)
        } else {
            combine(profileDao.observeById(id), kids.approvals) { p, all ->
                addonAccessOf(p?.isKids, all[id].orEmpty(), allowKids, id)
            }
        }
    }

/** What the stream list says about the last stream picked. */
enum class PlayNote { STARTING, HLS, NOT_PLAYABLE, FAILED }

/** Only an adult profile that has been read may play an addon stream. */
internal fun canPlay(access: DiscoveryAccess): Boolean = access == DiscoveryAccess.OPEN
