package tv.own.owntv.gambit.ui.trakt

/**
 * Whether More shows its Trakt entry: only for a profile read as not kids ([isKids] false), and only in a
 * build with a Trakt Client ID. Fails closed: a profile not read yet, no active profile, or one that
 * can't be found ([isKids] null) hides it.
 */
fun traktEntryShown(isKids: Boolean?, configured: Boolean): Boolean = configured && isKids == false
