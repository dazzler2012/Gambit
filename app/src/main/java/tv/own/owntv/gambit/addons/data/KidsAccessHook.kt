package tv.own.owntv.gambit.addons.data

import org.koin.core.context.GlobalContext

/**
 * Called by the profile editor before a profile edit is applied. When the edit leaves the profile not
 * Kids, its approvals are cleared first, so turning Kids off and on again starts Closed. It takes
 * [KidsAddonAccess] from Koin so the editor's constructor and DI stay untouched; if Koin can't give it, the
 * edit still goes ahead and the watcher ([KidsAccessPruner]) clears the entry.
 */
suspend fun beforeProfileEdit(profileId: Long, isKids: Boolean) {
    val access = try {
        GlobalContext.get().get<KidsAddonAccess>()
    } catch (e: Exception) {
        return
    }
    beforeProfileEdit(access, profileId, isKids)
}

internal suspend fun beforeProfileEdit(access: KidsAddonAccess, profileId: Long, isKids: Boolean) {
    if (!isKids) access.clearNow(profileId)
}
