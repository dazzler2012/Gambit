package tv.own.owntv.gambit.ui.discovery

/**
 * Whether Discovery may offer Trakt: an adult profile that has been read, in a build with a Trakt Client
 * ID. Kids, Closed and Unknown never get it.
 */
internal fun traktAllowed(access: AddonAccess, configured: Boolean): Boolean =
    configured && access == AddonAccess.Adult
