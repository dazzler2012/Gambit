package tv.own.owntv.gambit.trakt

import tv.own.owntv.BuildConfig

/**
 * Reads the Trakt Client ID this build was made with, and turns it into a [TraktConfig] only when it is
 * usable. A blank or missing ID is normal (a fork build, a build without the GitHub secret) and must
 * never crash the app: [config] then returns null, [TraktConfig] is never constructed (it rejects a
 * blank ID), and the Trakt screen stays hidden.
 *
 * The Client ID is not a secret for device-code sign-in (no client secret is used), but it is still
 * never logged or printed. Nothing here puts it in a message.
 */
object GambitTraktBuild {
    /** True when this build carries a Client ID, so Trakt can be offered. */
    val isConfigured: Boolean get() = clean(BuildConfig.TRAKT_CLIENT_ID) != null

    /** The config for this build, or null when Trakt is not configured (or the value is unusable). */
    fun config(): TraktConfig? = configFrom(BuildConfig.TRAKT_CLIENT_ID)

    /** [raw] trimmed, or null when it is null or blank. */
    internal fun clean(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Builds a config from [raw] with [make], but never calls [make] for a blank value, and treats any
     * failure of [make] as "not configured" rather than letting it escape.
     */
    internal fun configFrom(raw: String?, make: (String) -> TraktConfig = { TraktConfig(it) }): TraktConfig? {
        val id = clean(raw) ?: return null
        return try {
            make(id)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
