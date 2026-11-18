package tv.own.owntv.gambit.ui.trakt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import tv.own.owntv.gambit.trakt.GambitTrakt

/**
 * More → Trakt: the sign-in. A thin wrapper over [TraktSignInModel], which holds the logic. Polling
 * runs in [viewModelScope], so it also stops when the screen is gone; the screen calls [cancel] on Back.
 */
class TraktSignInViewModel(trakt: GambitTrakt) : ViewModel() {
    private val model = TraktSignInModel(trakt.client, viewModelScope, trakt.renewalCheck)

    val state: StateFlow<TraktSignInState> = model.state

    val renewal: StateFlow<TraktRenewalState> = model.renewal
    val canCheckRenewal: Boolean get() = model.canCheckRenewal

    fun start() = model.start()

    /** Back: stop polling. */
    fun cancel() = model.cancel()

    fun clearFailure() = model.clearFailure()

    fun signOut() = model.signOut()

    /** The screen opened: read the saved sign-in again. */
    fun recheck() = model.recheck()

    /** Temporary debug button: prove renewal against the live Trakt service. */
    fun checkRenewal() = model.checkRenewal()
}
