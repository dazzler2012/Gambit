package tv.own.owntv.gambit.trakt

/**
 * Proves token renewal against the live Trakt service without waiting for a token to expire. Three
 * steps, each on the tokens the previous one left:
 * 1. **Forced:** [TraktSession.refresh] renews whatever the expiry.
 * 2. **Automatic:** the saved expiry is set to now, then one signed-in call is made; the session must
 *    renew before the call (its 5-minute margin).
 * 3. **After a rejected token:** a wrong access token is saved (the refresh token is kept), then the
 *    same call; Trakt rejects it, and the session must renew once and retry. If no renewal happened,
 *    the previous good pair is put back so the app is never left holding the wrong token.
 *
 * Results carry only yes/no, an expiry time and a typed [TraktError]: never a token. A step that ends in
 * an error stops the check; the later steps are not run (null). If Trakt rejects the refresh token, the
 * session clears the sign-in (the user signs in again): that is the failure this check exists to find.
 */
class TraktRenewalCheck internal constructor(
    private val client: TraktClient,
    private val store: TraktTokenStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * One step's outcome. [renewed]: the saved refresh token changed (Trakt hands out a new one with
     * every renewal). [error]: the renewal or the test call failed. [expiresAtEpochSeconds]: the saved
     * expiry afterwards, when signed in.
     */
    data class Step(val renewed: Boolean, val error: TraktError?, val expiresAtEpochSeconds: Long?)

    /** Null steps were not run, because an earlier one failed. */
    data class Result(val forced: Step, val automatic: Step?, val afterRejected: Step?)

    suspend fun run(): Result {
        val forced = forced()
        if (forced.error != null) return Result(forced, null, null)
        val automatic = automatic()
        if (automatic.error != null) return Result(forced, automatic, null)
        return Result(forced, automatic, afterRejected())
    }

    private suspend fun forced(): Step {
        val before = when (val read = read()) {
            is TraktResult.Failure -> return failed(read.error)
            is TraktResult.Success -> read.value ?: return failed(TraktError.NotSignedIn)
        }
        return when (val r = client.session.refresh()) {
            is TraktResult.Success -> Step(r.value.refreshToken != before.refreshToken, null, r.value.expiresAtEpochSeconds)
            is TraktResult.Failure -> after(before, r.error)
        }
    }

    private suspend fun automatic(): Step {
        val before = when (val read = read()) {
            is TraktResult.Failure -> return failed(read.error)
            is TraktResult.Success -> read.value ?: return failed(TraktError.NotSignedIn)
        }
        val dueNow = TraktTokens(before.accessToken, before.refreshToken, nowMillis() / 1000, before.scope)
        storing { store.write(dueNow) }.errorOrNull()?.let { return failed(it) }
        return after(before, testCall())
    }

    private suspend fun afterRejected(): Step {
        val good = when (val read = read()) {
            is TraktResult.Failure -> return failed(read.error)
            is TraktResult.Success -> read.value ?: return failed(TraktError.NotSignedIn)
        }
        val rejected = TraktTokens(WRONG_ACCESS_TOKEN, good.refreshToken, good.expiresAtEpochSeconds, good.scope)
        storing { store.write(rejected) }.errorOrNull()?.let { return failed(it) }
        val error = testCall()
        val now = read().getOrNull()
        // No renewal happened (Trakt answered something other than 401, or the network failed): put the
        // good pair back. Never after a renewal: the old refresh token is dead by then.
        if (now != null && now.accessToken == WRONG_ACCESS_TOKEN) {
            storing { store.write(good) }.errorOrNull()?.let { return failed(it) }
            return Step(renewed = false, error = error, expiresAtEpochSeconds = good.expiresAtEpochSeconds)
        }
        return after(good, error)
    }

    /** One small signed-in call: the newest history entry. Null when it worked. */
    private suspend fun testCall(): TraktError? = client.sync.history(page = 1, limit = 1).errorOrNull()

    /** The step's result, judged by what is saved now compared with [before]. */
    private suspend fun after(before: TraktTokens, error: TraktError?): Step {
        val now = read().getOrNull()
        return Step(renewed = now != null && now.refreshToken != before.refreshToken, error = error, expiresAtEpochSeconds = now?.expiresAtEpochSeconds)
    }

    private suspend fun read(): TraktResult<TraktTokens?> = storing { store.read() }

    private fun failed(error: TraktError) = Step(renewed = false, error = error, expiresAtEpochSeconds = null)

    internal companion object {
        /** Not a real token: Trakt must reject it. */
        const val WRONG_ACCESS_TOKEN = "gambit-renewal-check-not-a-token"
    }
}
