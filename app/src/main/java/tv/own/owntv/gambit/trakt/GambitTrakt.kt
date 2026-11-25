package tv.own.owntv.gambit.trakt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Gambit's Trakt, as Koin hands it out. [client] is null when this build has no usable Client ID (or
 * building it failed): nothing Trakt then runs, the Trakt screen stays hidden, and the app carries on.
 * See [GambitTraktBuild].
 */
class GambitTrakt(val client: TraktClient?) {
    val isConfigured: Boolean get() = client != null

    private val _signOuts = MutableStateFlow(0)

    /** Goes up on every sign-out in More → Trakt, so screens holding Trakt data can drop it. */
    val signOuts: StateFlow<Int> = _signOuts.asStateFlow()

    /** More → Trakt is signing out. */
    fun noteSignOut() = _signOuts.update { it + 1 }
}
