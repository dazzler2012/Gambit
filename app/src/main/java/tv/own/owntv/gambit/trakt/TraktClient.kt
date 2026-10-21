package tv.own.owntv.gambit.trakt

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

/**
 * Gambit's entry point to Trakt: sign-in, scrobbling and sync, all sharing one session.
 *
 * Pass the app's shared [OkHttpClient] to reuse its connection pool; [httpClient] is not changed.
 * [nowMillis] and [sleep] are there so tests can run the clock themselves.
 */
class TraktClient(
    config: TraktConfig,
    tokenStore: TraktTokenStore,
    httpClient: OkHttpClient = defaultHttpClient(),
    nowMillis: () -> Long = System::currentTimeMillis,
    sleep: suspend (millis: Long) -> Unit = { delay(it) },
) {
    private val http = TraktHttp(config, httpClient, nowMillis)

    val session = TraktSession(config, http, tokenStore, nowMillis)
    val deviceAuth = TraktDeviceAuth(config, http, tokenStore, nowMillis, sleep)
    val scrobbler = TraktScrobbler(http, session)
    val sync = TraktSync(http, session)

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
