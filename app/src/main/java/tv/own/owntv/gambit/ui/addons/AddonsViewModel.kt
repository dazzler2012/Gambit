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
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.gambit.addons.data.AddonAddResult
import tv.own.owntv.gambit.addons.data.AddonStore
import tv.own.owntv.gambit.addons.data.InstalledAddon
import tv.own.owntv.gambit.addons.data.KidsAccessRelease
import tv.own.owntv.gambit.addons.net.AddonUrl
import tv.own.owntv.gambit.trakt.GambitTraktBuild
import tv.own.owntv.gambit.ui.trakt.traktEntryShown

/** More → Addons: the device's addon list and adding one by typing its address. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddonsViewModel(
    private val store: AddonStore,
    settings: SettingsRepository,
    profileDao: ProfileDao,
) : ViewModel() {

    val addons: StateFlow<List<InstalledAddon>> = store.registry
        .map { it.all }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.registry.value.all)

    /** True when a saved list exists but couldn't be read, so the addons need adding again. */
    val unreadable: StateFlow<Boolean> = store.unreadable

    /** True on a kids profile: Addons is hidden there, and the page shows nothing. */
    val kids: StateFlow<Boolean> = settings.activeProfileId
        .flatMapLatest { id -> if (id < 0) flowOf(null) else profileDao.observeById(id) }
        .map { it?.isKids == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Whether More shows Trakt (see [traktEntryShown]). Hidden until the profile is read, and hidden when
     * there is no active profile or it can't be found: unlike [kids], an unknown profile is not an adult one.
     */
    val traktEntry: StateFlow<Boolean> = settings.activeProfileId
        .flatMapLatest { id -> if (id < 0) flowOf(null) else profileDao.observeById(id) }
        .map { traktEntryShown(it?.isKids, GambitTraktBuild.isConfigured) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Every profile, for the adult PIN prompt in front of Kids access. */
    val profiles: StateFlow<List<ProfileEntity>> = profileDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Whether the Kids access entry shows: on an adult profile, and only while the release lock allows it. */
    val kidsAccessEntry: StateFlow<Boolean> = kids.map { kidsAccessEntryVisible(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _typed = MutableStateFlow<AddonAddResult?>(null)
    /** The result of the last typed address, for the page to say so (null = nothing to say). */
    val typed: StateFlow<AddonAddResult?> = _typed.asStateFlow()

    fun addTyped(address: String) {
        if (kids.value) return
        viewModelScope.launch { _typed.value = store.add(address) }
    }

    fun clearTyped() { _typed.value = null }

    fun remove(url: AddonUrl) = store.remove(url)
    fun setEnabled(url: AddonUrl, on: Boolean) = store.setEnabled(url, on)
    fun move(url: AddonUrl, delta: Int) = store.move(url, delta)
}

/** The Kids access entry is hidden on kids profiles and while [KidsAccessRelease] has not allowed it. */
internal fun kidsAccessEntryVisible(kidsProfile: Boolean, allowKids: Boolean = KidsAccessRelease.ALLOWED): Boolean =
    !kidsProfile && allowKids
