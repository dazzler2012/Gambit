package tv.own.owntv.gambit.trakt

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Keeps the signed-in user's access token usable. Before each call it refreshes a token that is about
 * to expire, and after a 401 it refreshes once and retries once. Refreshes are serialised, so two
 * calls that both see an old token cause one refresh, not two (Trakt refresh tokens are single use).
 */
class TraktSession internal constructor(
    private val config: TraktConfig,
    private val http: TraktHttp,
    private val store: TraktTokenStore,
    private val nowMillis: () -> Long,
) {
    private val refreshLock = Mutex()

    suspend fun isSignedIn(): TraktResult<Boolean> = storing { store.read() != null }

    /** Forgets the stored tokens. Does not call Trakt. */
    suspend fun signOut(): TraktResult<Unit> = storing { store.clear() }

    /** Refreshes now, whatever the current token's expiry. */
    suspend fun refresh(): TraktResult<TraktTokens> = refreshLock.withLock {
        when (val current = storing { store.read() }) {
            is TraktResult.Failure -> current
            is TraktResult.Success -> current.value?.let { refreshLocked(it) } ?: TraktResult.Failure(TraktError.NotSignedIn)
        }
    }

    /** Runs [call] with a valid access token, refreshing before it or after a 401 as needed. */
    internal suspend fun <T> authorized(call: suspend (accessToken: String) -> TraktResult<T>): TraktResult<T> {
        val stored = when (val read = storing { store.read() }) {
            is TraktResult.Failure -> return read
            is TraktResult.Success -> read.value ?: return TraktResult.Failure(TraktError.NotSignedIn)
        }
        val usable = if (stored.expiresWithin(REFRESH_MARGIN_SECONDS, nowSeconds())) {
            when (val fresh = refreshUnless(stored)) {
                is TraktResult.Failure -> return fresh
                is TraktResult.Success -> fresh.value
            }
        } else {
            stored
        }
        val first = call(usable.accessToken)
        if (first.errorOrNull() != TraktError.Unauthorized) return first
        return when (val fresh = refreshUnless(usable)) {
            is TraktResult.Failure -> fresh
            is TraktResult.Success -> call(fresh.value.accessToken)
        }
    }

    /**
     * Refreshes [stale] unless another call already replaced it while this one waited for the lock,
     * in which case the newer tokens are used as they are.
     */
    private suspend fun refreshUnless(stale: TraktTokens): TraktResult<TraktTokens> = refreshLock.withLock {
        val current = when (val read = storing { store.read() }) {
            is TraktResult.Failure -> return@withLock read
            is TraktResult.Success -> read.value ?: return@withLock TraktResult.Failure(TraktError.NotSignedIn)
        }
        if (current.accessToken != stale.accessToken && !current.expiresWithin(REFRESH_MARGIN_SECONDS, nowSeconds())) {
            TraktResult.Success(current)
        } else {
            refreshLocked(current)
        }
    }

    private suspend fun refreshLocked(current: TraktTokens): TraktResult<TraktTokens> {
        val body = JSONObject()
            .put("refresh_token", current.refreshToken)
            .put("client_id", config.clientId)
            .put("redirect_uri", config.redirectUri)
            .put("grant_type", "refresh_token")
        config.clientSecret?.let { body.put("client_secret", it) }

        val reply = when (val sent = http.send("POST", "oauth/token", body, acceptAnyStatus = true)) {
            is TraktResult.Failure -> return sent
            is TraktResult.Success -> sent.value
        }
        if (reply.status == 400 || reply.status == 401) {
            // The refresh token is dead (revoked, expired or already used): keeping it would only fail again.
            storing { store.clear() }.errorOrNull()?.let { return TraktResult.Failure(it) }
            return TraktResult.Failure(TraktError.SignInExpired)
        }
        if (reply.status !in 200..299) return TraktResult.Failure(http.errorFor(reply))

        val tokens = when (val parsed = parsing { parseObject(reply.body).toTokens() }) {
            is TraktResult.Failure -> return parsed
            is TraktResult.Success -> parsed.value
        }
        return storing { store.write(tokens) }.map { tokens }
    }

    private fun nowSeconds() = nowMillis() / 1000

    companion object {
        /** Refresh when the access token has less than this left, so a call never starts with a dying token. */
        const val REFRESH_MARGIN_SECONDS = 5 * 60L
    }
}
