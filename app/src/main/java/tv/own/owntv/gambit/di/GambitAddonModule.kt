package tv.own.owntv.gambit.di

import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tv.own.owntv.gambit.addons.GambitAddonVault
import tv.own.owntv.gambit.addons.GambitSecretStore
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.data.AddonStore
import tv.own.owntv.gambit.addons.net.AddonClient
import tv.own.owntv.gambit.ui.addons.AddonsViewModel
import tv.own.owntv.gambit.ui.discovery.DiscoveryViewModel

/**
 * Gambit's Stremio addons (Phase 2): the encrypted store and More → Addons. The addon client gets its
 * own plain OkHttp client, not the app's shared one, so no app-wide interceptor (logging or otherwise)
 * ever sees addon URLs, which carry the user's keys.
 */
val gambitAddonModule = module {
    single { GambitSecretStore() }
    single { AddonRepository(AddonClient(OkHttpClient())) }
    single { AddonStore(get(), GambitAddonVault(androidContext(), get())) }
    viewModelOf(::AddonsViewModel)
    viewModelOf(::DiscoveryViewModel)
}
