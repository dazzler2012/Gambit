package tv.own.owntv.gambit.trakt

import org.json.JSONObject

/**
 * Trakt's device-code sign-in, for a TV with no keyboard:
 * 1. [requestCode] gets a short code; show its user code and verification URL.
 * 2. [awaitApproval] polls Trakt at the given interval until the user approves on another device,
 *    then saves the tokens to the [TraktTokenStore].
 *
 * Cancel the coroutine running [awaitApproval] to stop polling (for example when the screen closes).
 */
class TraktDeviceAuth internal constructor(
    private val config: TraktConfig,
    private val http: TraktHttp,
    private val store: TraktTokenStore,
    private val nowMillis: () -> Long,
    private val sleep: suspend (millis: Long) -> Unit,
) {
    suspend fun requestCode(): TraktResult<TraktDeviceCode> =
        http.send("POST", "oauth/device/code", JSONObject().put("client_id", config.clientId)).flatMap { reply ->
            parsing {
                val json = parseObject(reply.body)
                TraktDeviceCode(
                    deviceCode = json.requireString("device_code"),
                    userCode = json.requireString("user_code"),
                    verificationUrl = json.requireString("verification_url"),
                    expiresInSeconds = json.requireLong("expires_in"),
                    intervalSeconds = json.requireLong("interval").coerceAtLeast(1),
                )
            }
        }

    /**
     * Polls until Trakt answers with tokens or a final outcome. Pending and transient failures
     * (timeout, network, 5xx) keep polling until the code expires; 429 slows polling down.
     * Ends with [TraktError.CodeExpired], [TraktError.CodeDenied], [TraktError.CodeAlreadyUsed],
     * [TraktError.InvalidCode], or any other error that makes polling pointless.
     */
    suspend fun awaitApproval(code: TraktDeviceCode): TraktResult<TraktTokens> {
        val deadline = nowMillis() + code.expiresInSeconds * 1000
        var intervalMillis = code.intervalSeconds * 1000
        while (true) {
            sleep(intervalMillis)
            if (nowMillis() >= deadline) return TraktResult.Failure(TraktError.CodeExpired)

            when (val step = pollOnce(code)) {
                is Poll.Approved -> return storing { store.write(step.tokens) }.map { step.tokens }
                is Poll.Finished -> return TraktResult.Failure(step.error)
                Poll.Pending -> Unit
                is Poll.SlowDown -> intervalMillis = maxOf(intervalMillis + SLOW_DOWN_STEP_MILLIS, step.retryAfterMillis ?: 0)
            }
        }
    }

    private sealed interface Poll {
        data class Approved(val tokens: TraktTokens) : Poll

        data object Pending : Poll

        data class SlowDown(val retryAfterMillis: Long?) : Poll

        data class Finished(val error: TraktError) : Poll
    }

    private suspend fun pollOnce(code: TraktDeviceCode): Poll {
        val body = JSONObject()
            .put("code", code.deviceCode)
            .put("client_id", config.clientId)
        config.clientSecret?.let { body.put("client_secret", it) }

        val reply = when (val sent = http.send("POST", "oauth/device/token", body, acceptAnyStatus = true)) {
            is TraktResult.Success -> sent.value
            is TraktResult.Failure -> return when (sent.error) {
                TraktError.Timeout, is TraktError.Network -> Poll.Pending
                else -> Poll.Finished(sent.error)
            }
        }
        return when (reply.status) {
            200 -> when (val parsed = parsing { parseObject(reply.body).toTokens() }) {
                is TraktResult.Success -> Poll.Approved(parsed.value)
                is TraktResult.Failure -> Poll.Finished(parsed.error)
            }
            400 -> Poll.Pending
            404 -> Poll.Finished(TraktError.InvalidCode)
            409 -> Poll.Finished(TraktError.CodeAlreadyUsed)
            410 -> Poll.Finished(TraktError.CodeExpired)
            418 -> Poll.Finished(TraktError.CodeDenied)
            429 -> Poll.SlowDown(http.retryAfterSeconds(reply.headers)?.times(1000))
            in 500..599 -> Poll.Pending
            else -> Poll.Finished(http.errorFor(reply))
        }
    }

    companion object {
        /** How much longer to wait between polls after each 429, when Trakt does not say. */
        const val SLOW_DOWN_STEP_MILLIS = 5_000L
    }
}
