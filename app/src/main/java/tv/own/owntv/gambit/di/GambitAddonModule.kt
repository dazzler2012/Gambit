package tv.own.owntv.gambit.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tv.own.owntv.gambit.addons.AddonStore
import tv.own.owntv.gambit.addons.GambitAddonServer
import tv.own.owntv.gambit.addons.GambitSecretStore
import tv.own.owntv.gambit.ui.addons.AddonsViewModel

/** Gambit's Stremio addons (Phase 2): the encrypted store, the phone page and More → Addons. */
val gambitAddonModule = module {
    single { GambitSecretStore() }
    single { AddonStore(androidContext(), get()) }
    single { GambitAddonServer(androidContext()) }
    viewModelOf(::AddonsViewModel)
}
