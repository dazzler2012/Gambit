package tv.own.owntv.gambit.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tv.own.owntv.gambit.trakt.GambitTrakt
import tv.own.owntv.gambit.trakt.GambitTraktBuild
import tv.own.owntv.gambit.trakt.GambitTraktTokenStore
import tv.own.owntv.gambit.trakt.GambitTraktVault
import tv.own.owntv.gambit.trakt.TraktClient
import tv.own.owntv.gambit.ui.trakt.TraktSignInViewModel

/**
 * Gambit's Trakt (Phase 3): the client, its encrypted token store and the sign-in ViewModel. A build
 * with no Client ID gets a [GambitTrakt] whose client is null, never a crash: the config is built only
 * for a usable ID, and any failure while building the client also reads as "not configured". The client
 * gets its own plain OkHttp client (TraktClient's default), so no app-wide interceptor ever sees tokens.
 * The Keystore key comes from the shared [tv.own.owntv.gambit.addons.GambitSecretStore] (gambitAddonModule).
 */
val gambitTraktModule = module {
    single {
        val config = GambitTraktBuild.config()
        val client = config?.let {
            runCatching { TraktClient(it, GambitTraktTokenStore(GambitTraktVault(androidContext(), get()))) }.getOrNull()
        }
        GambitTrakt(client)
    }
    viewModelOf(::TraktSignInViewModel)
}
