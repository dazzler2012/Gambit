package tv.own.owntv.gambit.ui.trakt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.own.owntv.gambit.trakt.TraktClient
import tv.own.owntv.gambit.trakt.TraktError
import tv.own.owntv.gambit.trakt.TraktResult

/**
 * What the Trakt sign-in screen shows. Failures carry the typed [TraktError]; the screen maps each type
 * to its own text. [TraktError.description] is for logs and is never shown.
 */
sealed interface TraktSignInState {
    /** This build has no Trakt Client ID. The screen is hidden, so nobody normally sees this. */
    data object NotConfigured : TraktSignInState

    /** The saved sign-in is being read. */
    data object Checking : TraktSignInState

    data object SignedOut : TraktSignInState

    /** Asking Trakt for a code. */
    data object Starting : TraktSignInState

    /**
     * Show [verificationUrl] (as a QR code and as text) and [userCode]; the code works until
     * [expiresAtMillis] (epoch millis). [toString] never prints the code.
     */
    class Waiting(val userCode: String, val verificationUrl: String, val expiresAtMillis: Long) : TraktSignInState {
        override fun toString(): String = "TraktSignInState.Waiting"
    }

    data object SignedIn : TraktSignInState

    data class Failed(val error: TraktError) : TraktSignInState
}

/**
 * The sign-in state machine, free of Android so it runs in plain JVM tests. [scope] is the screen's
 * scope (viewModelScope): when it ends, polling stops. [cancel] is for Back. Polling itself (Trakt's
 * interval, slow-down, the 10-minute expiry and the final outcomes) is [TraktClient.deviceAuth]'s job.
 *
 * With a null [client] (no Client ID in this build) every action does nothing.
 */
class TraktSignInModel(
    private val client: TraktClient?,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow<TraktSignInState>(
        if (client == null) TraktSignInState.NotConfigured else TraktSignInState.Checking,
    )
    val state: StateFlow<TraktSignInState> = mutableState.asStateFlow()

    private var job: Job? = null

    init {
        if (client != null) {
            scope.launch {
                val found = when (val read = client.session.isSignedIn()) {
                    is TraktResult.Success -> if (read.value) TraktSignInState.SignedIn else TraktSignInState.SignedOut
                    is TraktResult.Failure -> TraktSignInState.Failed(read.error)
                }
                // Only if nothing (a sign-in started meanwhile) changed the state in the meantime.
                mutableState.compareAndSet(TraktSignInState.Checking, found)
            }
        }
    }

    /** Gets a code and polls until the user approves, or it ends. Starting again replaces a running sign-in. */
    fun start() {
        val trakt = client ?: return
        job?.cancel()
        mutableState.value = TraktSignInState.Starting
        job = scope.launch {
            when (val got = trakt.deviceAuth.requestCode()) {
                is TraktResult.Failure -> publish(TraktSignInState.Failed(got.error))
                is TraktResult.Success -> {
                    val code = got.value
                    publish(
                        TraktSignInState.Waiting(code.userCode, code.verificationUrl, nowMillis() + code.expiresInSeconds * 1000),
                    )
                    publish(
                        when (val done = trakt.deviceAuth.awaitApproval(code)) {
                            is TraktResult.Success -> TraktSignInState.SignedIn
                            is TraktResult.Failure -> TraktSignInState.Failed(done.error)
                        },
                    )
                }
            }
        }
    }

    /** Back: stops polling and returns to signed out. Does nothing once signed in or on a failure screen. */
    fun cancel() {
        job?.cancel()
        job = null
        val now = mutableState.value
        if (now is TraktSignInState.Waiting || now == TraktSignInState.Starting) mutableState.value = TraktSignInState.SignedOut
    }

    /** Leaves a failure screen ("try again" shows the start button again). */
    fun clearFailure() {
        if (mutableState.value is TraktSignInState.Failed) mutableState.value = TraktSignInState.SignedOut
    }

    /** Forgets the saved sign-in on this TV (Trakt itself is not told). */
    fun signOut() {
        val trakt = client ?: return
        job?.cancel()
        job = null
        scope.launch {
            mutableState.value = when (val out = trakt.session.signOut()) {
                is TraktResult.Success -> TraktSignInState.SignedOut
                is TraktResult.Failure -> TraktSignInState.Failed(out.error)
            }
        }
    }

    /**
     * Reads the saved sign-in again (the screen calls this when it opens): a renewal that Trakt refused
     * in the background clears the sign-in, and the screen should say so. Leaves a sign-in in progress
     * or a failure screen alone.
     */
    fun recheck() {
        val trakt = client ?: return
        scope.launch {
            val before = mutableState.value
            if (before != TraktSignInState.SignedIn && before != TraktSignInState.SignedOut) return@launch
            val found = when (val read = trakt.session.isSignedIn()) {
                is TraktResult.Success -> if (read.value) TraktSignInState.SignedIn else TraktSignInState.SignedOut
                is TraktResult.Failure -> TraktSignInState.Failed(read.error)
            }
            mutableState.compareAndSet(before, found)
        }
    }

    /** Writes [next] unless this sign-in was cancelled or replaced while a Trakt call was in flight. */
    private suspend fun publish(next: TraktSignInState) {
        currentCoroutineContext().ensureActive()
        mutableState.value = next
    }
}
