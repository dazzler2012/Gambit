package tv.own.owntv.gambit

import android.content.Context
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.model.HomeConfig
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Home's "Live sports" row slot shows addon posters in Gambit (see `gambit/ui/home`). Home rows are kept
 * per profile in core's settings, so each profile gets the slot set once: shown on adult profiles, hidden
 * on kids profiles. That covers every profile there is on the first start of this build, and every profile
 * made later as soon as it appears. A profile the change has reached is remembered in Gambit's own
 * preferences, so hiding or showing the row again in Settings › Layout › Home screen sticks.
 *
 * The first version of this step (key `home_live_sports_hidden_v1`) hid the row on every profile; that key
 * is left as it was and no longer read.
 */
object GambitHomeRows {
    private const val PREFS = "gambit"
    private const val KEY_DONE = "home_live_sports_shown_v2"

    /** The row slot Gambit uses for addon posters. */
    val ROW: HomeRow = HomeRow.LIVE_SPORTS

    /** The profiles that still need the change, in the order given. */
    fun pending(profileIds: Collection<Long>, done: Set<Long>): List<Long> = profileIds.filter { it !in done }

    /** [config] with the row shown; every other choice kept. */
    fun showRow(config: HomeConfig): HomeConfig =
        if (ROW !in config.hidden) config else config.copy(hidden = config.hidden - ROW)

    /** [config] with the row hidden; every other choice kept. */
    fun hideRow(config: HomeConfig): HomeConfig =
        if (ROW in config.hidden) config else config.copy(hidden = config.hidden + ROW)

    /** The change for one profile: hidden on a kids profile, shown on any other. */
    fun change(isKids: Boolean, config: HomeConfig): HomeConfig = if (isKids) hideRow(config) else showRow(config)

    /** Profile ids as kept in preferences, and back. Anything unreadable is dropped. */
    fun encode(ids: Set<Long>): Set<String> = ids.mapTo(HashSet()) { it.toString() }
    fun decode(stored: Set<String>?): Set<Long> = stored.orEmpty().mapNotNullTo(HashSet()) { it.toLongOrNull() }

    /**
     * Sets the row on each profile not handled yet, then keeps watching for new profiles. Runs for the
     * life of the app (it is the last step of [GambitDefaults.applyOnce], in the app's own scope).
     */
    suspend fun keepSet(context: Context, settings: SettingsRepository, profileDao: ProfileDao) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        profileDao.observeAll().collect { profiles ->
            val done = decode(prefs.getStringSet(KEY_DONE, null))
            val todo = pending(profiles.map { it.id }, done)
            if (todo.isEmpty()) return@collect
            val kids = profiles.filter { it.isKids }.mapTo(HashSet()) { it.id }
            val now = HashSet(done)
            for (id in todo) {
                settings.updateHomeConfig(id) { change(id in kids, it) }
                now += id
                prefs.edit().putStringSet(KEY_DONE, encode(now)).apply()
            }
        }
    }
}
