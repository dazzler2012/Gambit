package tv.own.owntv.gambit.ui.trakt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import tv.own.owntv.gambit.trakt.GambitTrakt

/**
 * More → Trakt: the sign-in. A thin wrapper over [TraktSignInModel], which holds the logic. Polling
 * runs in [viewModelScope], so it also stops when the screen is gone; the screen calls [cancel] on Back.
 */
class TraktSignInViewModel(private val trakt: GambitTrakt) : ViewModel() {
    private val model = TraktSignInModel(trakt.client, viewModelScope)

    val state: StateFlow<TraktSignInState> = model.state

    fun start() = model.start()

    /** Back: stop polling. */
    fun cancel() = model.cancel()

    fun clearFailure() = model.clearFailure()

    /** Also tells other screens (Discovery's Trakt lists) to drop what they hold. */
    fun signOut() {
        trakt.noteSignOut()
        model.signOut()
    }

    /** The screen opened: read the saved sign-in again. */
    fun recheck() = model.recheck()
}
