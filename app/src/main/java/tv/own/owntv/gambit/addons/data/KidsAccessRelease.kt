package tv.own.owntv.gambit.addons.data

/**
 * The release lock for kids addon access. While [ALLOWED] is false every kids profile is treated as
 * closed (no addon content at all, exactly like before this feature) and the Kids access entry is hidden.
 * Lifted on its own commit, after the device checks.
 */
object KidsAccessRelease {
    const val ALLOWED = true
}
