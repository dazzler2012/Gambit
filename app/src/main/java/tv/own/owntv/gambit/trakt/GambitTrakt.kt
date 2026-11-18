package tv.own.owntv.gambit.trakt

/**
 * Gambit's Trakt, as Koin hands it out. [client] is null when this build has no usable Client ID (or
 * building it failed): nothing Trakt then runs, the Trakt screen stays hidden, and the app carries on.
 * See [GambitTraktBuild].
 */
class GambitTrakt(val client: TraktClient?) {
    val isConfigured: Boolean get() = client != null
}
