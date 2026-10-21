package tv.own.owntv.gambit.trakt

/**
 * The Trakt app's credentials and where to reach it. Nothing here is ever hard-coded: the caller
 * builds this from wherever the keys are kept (BuildConfig in the app, fake values in tests).
 *
 * [clientSecret] is optional. Trakt marks it deprecated for apps that run on a user's device and asks
 * that it only be sent from a server, so it is sent with token requests only when it is set.
 *
 * [toString] never prints either key.
 */
class TraktConfig(
    val clientId: String,
    val clientSecret: String? = null,
    val baseUrl: String = DEFAULT_BASE_URL,
    val userAgent: String = DEFAULT_USER_AGENT,
    val redirectUri: String = DEVICE_REDIRECT_URI,
) {
    init {
        require(clientId.isNotBlank()) { "Trakt client ID is blank" }
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) { "Trakt base URL is not http(s)" }
    }

    override fun toString(): String =
        "TraktConfig(clientId=$REDACTED, clientSecret=${if (clientSecret == null) "null" else REDACTED}, " +
            "baseUrl=$baseUrl, userAgent=$userAgent)"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.trakt.tv"
        const val DEFAULT_USER_AGENT = "Gambit"

        /** The out-of-band redirect URI Trakt uses for apps that sign in with a device code. */
        const val DEVICE_REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"
        internal const val API_VERSION = "2"
    }
}

/** Shown in place of any token, code or key in [toString] output and error text. */
internal const val REDACTED = "<redacted>"
