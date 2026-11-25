package tv.own.owntv.gambit.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.own.owntv.gambit.trakt.TraktClient
import tv.own.owntv.gambit.trakt.TraktError
import tv.own.owntv.gambit.trakt.TraktResult
import tv.own.owntv.gambit.trakt.errorOrNull
import tv.own.owntv.gambit.trakt.getOrNull

/** The Trakt account as Discovery sees it. */
sealed interface TraktAccount {
    data object Checking : TraktAccount

    /** Nobody is signed in on this TV: the page says to sign in at More → Trakt. */
    data object SignedOut : TraktAccount

    data object SignedIn : TraktAccount

    /** The saved sign-in could not be read. */
    data class Failed(val error: TraktError) : TraktAccount
}

/** One entry of the list drop-down. [name] is null for the watchlist (the screen names it). */
data class TraktListChoice(val source: TraktListSource, val name: String?)

/**
 * Trakt in Discovery. [active] while "Trakt" is the chosen type. [choices]: the watchlist, then the
 * personal lists in Trakt's order. [listsError] is set when the personal lists could not be read (the
 * watchlist is still offered).
 */
data class TraktDiscoveryState(
    val active: Boolean = false,
    val account: TraktAccount = TraktAccount.Checking,
    val choices: List<TraktListChoice> = emptyList(),
    val listsError: TraktError? = null,
    val selected: TraktListSource? = null,
)

/**
 * The Trakt choice of Discovery: the sign-in check, the list drop-down and the titles of the chosen list
 * (through [TraktListBoard]). Plain Kotlin; the view model hands it a scope.
 *
 * [allowed] is asked before every Trakt read: it must be true only for an adult profile in a build with
 * a Client ID. While it is false nothing here sends a request, and [accessChanged] forgets everything.
 * There is no kids gate: kids profiles never get Trakt, and nothing here reports to one.
 *
 * Calls come from one thread (the main one in the app).
 */
class TraktDiscovery(
    private val client: TraktClient?,
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val board: TraktListBoard? = client?.let { TraktListBoard(it.lists, scope, nowMillis) }

    /** The titles of the chosen list. */
    val list: StateFlow<TraktListState> = board?.state ?: MutableStateFlow(TraktListState()).asStateFlow()

    private val _state = MutableStateFlow(TraktDiscoveryState())
    val state: StateFlow<TraktDiscoveryState> = _state.asStateFlow()

    private var job: Job? = null

    /** Bumped on every new check; a reply applies only while its number is still current. */
    @Volatile private var generation = 0

    /** Whether "Trakt" is offered in the type drop-down. */
    val offered: Boolean get() = client != null && allowed()

    init {
        // A sign-in that ran out (or was cleared elsewhere) while reading a list: say so, keep nothing.
        board?.state?.onEach { s ->
            val error = when (val feed = s.feed) {
                is TraktListFeed.Failed -> feed.error
                is TraktListFeed.Loaded -> feed.error
                else -> null
            }
            if (error.isSignedOut()) showSignedOut()
        }?.launchIn(scope)
    }

    /** "Trakt" chosen in the type drop-down. False, and nothing happens, when Trakt is not allowed. */
    fun enter(): Boolean {
        if (!offered) return false
        _state.update { it.copy(active = true) }
        check()
        return true
    }

    /** Another type chosen. What was read stays kept (see [TraktListBoard]). */
    fun leave() {
        cancel()
        _state.update { it.copy(active = false) }
    }

    /** Discovery shown again while on Trakt: check the sign-in and the lists again. */
    fun refresh() {
        if (!_state.value.active) return
        if (!offered) forget() else check()
    }

    fun select(source: TraktListSource) {
        val s = _state.value
        if (!s.active || !offered || s.account != TraktAccount.SignedIn) return
        if (s.choices.none { it.source == source }) return
        _state.update { it.copy(selected = source) }
        board?.open(source)
    }

    /** Try again after the sign-in check or the list of lists failed. */
    fun retry() {
        if (!_state.value.active || !offered) return
        check()
    }

    /** Try again after the chosen list failed; goes on from where it stopped. */
    fun retryList() {
        if (!_state.value.active || !offered) return
        board?.retry()
    }

    /** Call whenever the profile's access changes: anything but an adult profile forgets everything. */
    fun accessChanged() {
        if (!offered) forget()
    }

    /** Profile switch, or access no longer adult: drops every list and leaves Trakt. */
    fun forget() {
        cancel()
        board?.forget()
        _state.value = TraktDiscoveryState()
    }

    /**
     * Signed out in More → Trakt: drops every list. Discovery stays on Trakt and checks the sign-in again
     * when it is next shown ([refresh]); the sign-out may still be on its way to storage now.
     */
    fun signedOut() {
        val wasActive = _state.value.active
        forget()
        if (wasActive && offered) _state.value = TraktDiscoveryState(active = true)
    }

    private fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    private fun showSignedOut() {
        cancel()
        board?.forget()
        _state.update { TraktDiscoveryState(active = it.active, account = TraktAccount.SignedOut) }
    }

    private fun check() {
        val trakt = client ?: return
        cancel()
        val gen = generation
        _state.update { it.copy(account = TraktAccount.Checking, listsError = null) }
        job = scope.launch {
            val signedIn = trakt.session.isSignedIn() // the saved sign-in only; no request
            if (gen != generation) return@launch
            when (signedIn) {
                is TraktResult.Failure -> {
                    _state.update { it.copy(account = TraktAccount.Failed(signedIn.error)) }
                    return@launch
                }
                is TraktResult.Success -> if (!signedIn.value) {
                    showSignedOut()
                    return@launch
                }
            }
            if (!offered) return@launch
            val mine = trakt.lists.mine()
            if (gen != generation) return@launch
            if (mine.errorOrNull().isSignedOut()) {
                showSignedOut()
                return@launch
            }
            val choices = listOf(TraktListChoice(TraktListSource.Watchlist, null)) +
                mine.getOrNull().orEmpty().map { TraktListChoice(TraktListSource.Personal(it.traktId), it.name) }
            val keep = _state.value.selected?.takeIf { s -> choices.any { it.source == s } } ?: TraktListSource.Watchlist
            _state.update { it.copy(account = TraktAccount.SignedIn, choices = choices, listsError = mine.errorOrNull(), selected = keep) }
            if (offered) board?.open(keep)
        }
    }

    private fun TraktError?.isSignedOut(): Boolean = this == TraktError.NotSignedIn || this == TraktError.SignInExpired
}
