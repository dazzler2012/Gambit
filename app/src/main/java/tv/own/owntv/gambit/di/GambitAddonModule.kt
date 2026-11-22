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
import tv.own.owntv.gambit.playback.GambitAddonPlayer
import tv.own.owntv.gambit.playback.GambitStreamProxy
import tv.own.owntv.R
import tv.own.owntv.gambit.ui.addons.AddonsViewModel
import tv.own.owntv.gambit.ui.discovery.DiscoveryViewModel
import tv.own.owntv.gambit.ui.home.HomeAddonRowViewModel

/**
 * Gambit's Stremio addons (Phase 2): the encrypted store and More → Addons. The addon client gets its
 * own plain OkHttp client, not the app's shared one, so no app-wide interceptor (logging or otherwise)
 * ever sees addon URLs, which carry the user's keys.
 */
val gambitAddonModule = module {
    single { GambitSecretStore() }
    single { AddonRepository(AddonClient(OkHttpClient())) }
    single { AddonStore(get(), GambitAddonVault(androidContext(), get())) }
    // Addon playback: the player only ever gets a 127.0.0.1 address from the proxy. Both use their own
    // plain clients, for the same reason as above (stream addresses carry debrid tokens).
    single { GambitStreamProxy(OkHttpClient()) }
    single {
        val context = androidContext()
        GambitAddonPlayer(get(), get(), OkHttpClient()) { index, lang, addonName ->
            val n = java.text.NumberFormat.getIntegerInstance(context.resources.configuration.locales[0]).format(index + 1)
            if (lang.isNullOrBlank()) context.getString(R.string.gambit_play_sub_label, n, addonName)
            else context.getString(R.string.gambit_play_sub_label_lang, n, lang, addonName)
        }
    }
    viewModelOf(::AddonsViewModel)
    viewModelOf(::DiscoveryViewModel)
    viewModelOf(::HomeAddonRowViewModel)
}
