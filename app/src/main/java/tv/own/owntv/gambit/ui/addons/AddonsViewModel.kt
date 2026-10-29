package tv.own.owntv.gambit.ui.addons

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.gambit.addons.AddonAddResult
import tv.own.owntv.gambit.addons.AddonStore
import tv.own.owntv.gambit.addons.GambitAddonServer
import tv.own.owntv.gambit.addons.InstalledAddon

/** More → Addons: the device's addon list, the phone page and the typed fallback. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddonsViewModel(
    private val store: AddonStore,
    private val server: GambitAddonServer,
    settings: SettingsRepository,
    profileDao: ProfileDao,
) : ViewModel() {

    val addons: StateFlow<List<InstalledAddon>> = store.addons

    /** True on a kids profile: Addons is hidden there, and the page shows nothing. */
    val kids: StateFlow<Boolean> = settings.activeProfileId
        .flatMapLatest { id -> if (id < 0) flowOf(null) else profileDao.observeById(id) }
        .map { it?.isKids == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val phone: StateFlow<GambitAddonServer.State> = server.state

    private val _typed = MutableStateFlow<AddonAddResult?>(null)
    /** The result of the last typed address, for the page to say so (null = nothing to say). */
    val typed: StateFlow<AddonAddResult?> = _typed.asStateFlow()

    fun openPhone() {
        if (kids.value) return
        server.start(CompanionLink.lanUrl(GambitAddonServer.PORT)) { url -> store.add(url) }
    }

    fun closePhone() = server.reset()

    fun addTyped(address: String) {
        if (kids.value) return
        viewModelScope.launch { _typed.value = store.add(address) }
    }

    fun clearTyped() { _typed.value = null }

    fun remove(id: String) = store.remove(id)
    fun setEnabled(id: String, on: Boolean) = store.setEnabled(id, on)
    fun move(id: String, delta: Int) = store.move(id, delta)

    override fun onCleared() { server.reset() }
}
