package tv.own.owntv.gambit.trakt

import java.time.Instant

/**
 * Every way a Trakt call can fail. None of these carries a token, a code, a response body or any
 * other user data, so they are safe to show and to log.
 */
sealed class TraktError {
    /** A short, plain description for logs and diagnostics. Never holds tokens or user data. */
    abstract val description: String

    override fun toString(): String = description

    /** The request took too long (connect, read or whole-call timeout). */
    data object Timeout : TraktError() {
        override val description = "Trakt request timed out"
    }

    /** No response at all: offline, DNS, TLS, connection reset. [kind] is the exception's class name. */
    data class Network(val kind: String) : TraktError() {
        override val description = "Trakt network error ($kind)"
    }

    /** 401: the access token was missing or rejected, and refreshing did not fix it. */
    data object Unauthorized : TraktError() {
        override val description = "Trakt rejected the sign-in (401)"
    }

    /** 403: the client ID is invalid or the app is not allowed to make this call. */
    data object Forbidden : TraktError() {
        override val description = "Trakt refused the request (403)"
    }

    /** 404: the item or endpoint does not exist. */
    data object NotFound : TraktError() {
        override val description = "Trakt could not find it (404)"
    }

    /**
     * 409: already done. For a scrobble, Trakt sends when the item was last scrobbled and when it can
     * be scrobbled again; both are null when the response does not say.
     */
    data class Conflict(val watchedAt: Instant?, val expiresAt: Instant?) : TraktError() {
        override val description = "Trakt says this was already done (409)"
    }

    /** 422: Trakt understood the request but would not act on it (for example scrobble progress under 1%). */
    data object Unprocessable : TraktError() {
        override val description = "Trakt would not process the request (422)"
    }

    /** 429: too many requests. Wait [retryAfterSeconds] (from the Retry-After header) when it is known. */
    data class RateLimited(val retryAfterSeconds: Long?) : TraktError() {
        override val description = "Trakt rate limit hit (429), retry after ${retryAfterSeconds ?: "unknown"} s"
    }

    /** 5xx: a problem on Trakt's side. */
    data class Server(val status: Int) : TraktError() {
        override val description = "Trakt server error ($status)"
    }

    /** Any other unexpected HTTP status. */
    data class Http(val status: Int) : TraktError() {
        override val description = "Unexpected Trakt response ($status)"
    }

    /** The response was not the JSON this client expects. [reason] is written by this client, never copied from the body. */
    data class Malformed(val reason: String) : TraktError() {
        override val description = "Trakt sent an unreadable response: $reason"
    }

    /** No tokens are stored; the user has to sign in first. */
    data object NotSignedIn : TraktError() {
        override val description = "Not signed in to Trakt"
    }

    /** The refresh token was rejected. The stored tokens have been cleared; the user has to sign in again. */
    data object SignInExpired : TraktError() {
        override val description = "Trakt sign-in expired; sign in again"
    }

    /** The token store failed to read, write or clear. [kind] is the exception's class name. */
    data class Storage(val kind: String) : TraktError() {
        override val description = "Trakt token storage failed ($kind)"
    }

    // ---- Device sign-in outcomes ----

    /** The device code ran out of time before the user approved it. Start sign-in again. */
    data object CodeExpired : TraktError() {
        override val description = "Trakt sign-in code expired"
    }

    /** The user chose to deny access on the Trakt site. */
    data object CodeDenied : TraktError() {
        override val description = "Trakt sign-in was denied"
    }

    /** The device code was already exchanged for tokens. */
    data object CodeAlreadyUsed : TraktError() {
        override val description = "Trakt sign-in code was already used"
    }

    /** Trakt does not know the device code. */
    data object InvalidCode : TraktError() {
        override val description = "Trakt sign-in code is not valid"
    }
}
