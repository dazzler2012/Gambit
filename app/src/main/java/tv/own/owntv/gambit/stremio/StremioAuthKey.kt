package tv.own.owntv.gambit.stremio

/**
 * The session key Stremio hands over once the phone approves a link code. It is a full sign-in to the
 * user's Stremio account, so it lives only in memory for the one import call that needs it: never
 * logged, saved, put in a URL, kept in screen state or shown. [toString] hides it.
 *
 * Only [StremioClient] reads [value], to put it in a request body.
 */
class StremioAuthKey internal constructor(internal val value: String) {
    override fun toString(): String = "StremioAuthKey(<hidden>)"
}
