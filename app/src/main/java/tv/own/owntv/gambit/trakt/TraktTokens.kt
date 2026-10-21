package tv.own.owntv.gambit.trakt

/**
 * A signed-in user's tokens. Trakt refresh tokens are single use: every refresh returns a new pair,
 * and the old refresh token stops working, so the new one must be saved straight away.
 *
 * [toString] never prints either token.
 */
class TraktTokens(
    val accessToken: String,
    val refreshToken: String,
    /** When [accessToken] stops working, in epoch seconds (Trakt's created_at + expires_in). */
    val expiresAtEpochSeconds: Long,
    val scope: String = "public",
) {
    /** True when the access token has expired or will within [marginSeconds]. */
    fun expiresWithin(marginSeconds: Long, nowEpochSeconds: Long): Boolean =
        nowEpochSeconds + marginSeconds >= expiresAtEpochSeconds

    override fun equals(other: Any?): Boolean =
        other is TraktTokens &&
            accessToken == other.accessToken &&
            refreshToken == other.refreshToken &&
            expiresAtEpochSeconds == other.expiresAtEpochSeconds &&
            scope == other.scope

    override fun hashCode(): Int =
        ((accessToken.hashCode() * 31 + refreshToken.hashCode()) * 31 + expiresAtEpochSeconds.hashCode()) * 31 + scope.hashCode()

    override fun toString(): String =
        "TraktTokens(accessToken=$REDACTED, refreshToken=$REDACTED, expiresAtEpochSeconds=$expiresAtEpochSeconds, scope=$scope)"
}

/**
 * Where the signed-in user's tokens are kept. Gambit's real storage plugs in here later; this package
 * only reads and writes through it.
 *
 * Implementations may throw: every call is wrapped and surfaces as [TraktError.Storage].
 */
interface TraktTokenStore {
    /** The saved tokens, or null when nobody is signed in. */
    suspend fun read(): TraktTokens?

    /** Replace the saved tokens. Called after sign-in and after every refresh. */
    suspend fun write(tokens: TraktTokens)

    /** Forget the saved tokens (sign-out, or a refresh token Trakt rejected). */
    suspend fun clear()
}
