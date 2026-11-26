package tv.own.owntv.gambit.ui.discovery

/**
 * Whether addon search exists for this access: an adult profile only. Never on a kids profile, approved
 * catalogues or not, and not before the profile has been read. Both the UI and every search call ask here.
 */
internal fun searchAllowed(access: AddonAccess): Boolean = access == AddonAccess.Adult
