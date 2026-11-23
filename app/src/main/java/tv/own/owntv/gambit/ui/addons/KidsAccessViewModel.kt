package tv.own.owntv.gambit.ui.addons

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.AddonStore
import tv.own.owntv.gambit.addons.data.KidsAddonAccess

/** One catalogue a parent can approve: its [key] (see CatalogRef.key), name and Stremio type. No address. */
data class CatalogChoice(val key: String, val name: String, val type: String)

/** The approvable catalogues of one addon, in the addon's order. */
data class CatalogGroup(val addonName: String, val catalogs: List<CatalogChoice>)

/** The browse catalogues of the enabled addons, grouped by addon, in addon order. */
internal fun catalogGroups(registry: AddonRegistry): List<CatalogGroup> =
    registry.browseCatalogs().groupBy { it.addon.url }.values.map { refs ->
        CatalogGroup(
            refs.first().addon.manifest.name,
            refs.map { CatalogChoice(it.key, it.catalog.name?.takeIf(String::isNotBlank) ?: it.catalog.id, it.catalog.type) },
        )
    }

/** The parent's Kids access page: which catalogues each kids profile may see. Shown only after an adult PIN. */
class KidsAccessViewModel(
    private val store: AddonStore,
    private val access: KidsAddonAccess,
    profileDao: ProfileDao,
) : ViewModel() {

    val kidsProfiles: StateFlow<List<ProfileEntity>> = profileDao.observeAll()
        .map { all -> all.filter { it.isKids } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val groups: StateFlow<List<CatalogGroup>> = store.registry.map(::catalogGroups)
        .stateIn(viewModelScope, SharingStarted.Eagerly, catalogGroups(store.registry.value))

    /** Kids profile id → approved catalogue keys. */
    val approvals: StateFlow<Map<Long, Set<String>>> = access.approvals

    fun setApproved(profileId: Long, key: String, on: Boolean) {
        viewModelScope.launch {
            val now = access.approved(profileId)
            access.set(profileId, if (on) now + key else now - key)
        }
    }

    /** Takes every approval away from the profile (it is Closed again). */
    fun turnOff(profileId: Long) {
        viewModelScope.launch { access.clearNow(profileId) }
    }
}
