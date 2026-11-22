package tv.own.owntv.gambit

import android.content.Context
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.model.HomeConfig
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Home's "Live sports" row is hidden in Gambit: the rail's Sports slot is Discovery now. Home rows are
 * kept per profile in core's settings, so each profile (adult and kids) gets the row hidden once: every
 * profile there is on the first start of this build, and every profile made later as soon as it appears.
 * A profile the change has reached is remembered in Gambit's own preferences, so showing the row again in
 * Settings › Layout › Home screen sticks.
 */
object GambitHomeRows {
    private const val PREFS = "gambit"
    private const val KEY_DONE = "home_live_sports_hidden_v1"

    /** The row Gambit hides. */
    val HIDDEN_ROW: HomeRow = HomeRow.LIVE_SPORTS

    /** The profiles that still need the row hidden, in the order given. */
    fun pending(profileIds: Collection<Long>, done: Set<Long>): List<Long> = profileIds.filter { it !in done }

    /** [config] with the Live sports row hidden; every other choice kept. */
    fun hideLiveSports(config: HomeConfig): HomeConfig =
        if (HIDDEN_ROW in config.hidden) config else config.copy(hidden = config.hidden + HIDDEN_ROW)

    /** Profile ids as kept in preferences, and back. Anything unreadable is dropped. */
    fun encode(ids: Set<Long>): Set<String> = ids.mapTo(HashSet()) { it.toString() }
    fun decode(stored: Set<String>?): Set<Long> = stored.orEmpty().mapNotNullTo(HashSet()) { it.toLongOrNull() }

    /**
     * Hides the row on each profile not handled yet, then keeps watching for new profiles. Runs for the
     * life of the app (it is the last step of [GambitDefaults.applyOnce], in the app's own scope).
     */
    suspend fun keepHidden(context: Context, settings: SettingsRepository, profileDao: ProfileDao) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        profileDao.observeAll().collect { profiles ->
            val done = decode(prefs.getStringSet(KEY_DONE, null))
            val todo = pending(profiles.map { it.id }, done)
            if (todo.isEmpty()) return@collect
            val now = HashSet(done)
            for (id in todo) {
                settings.updateHomeConfig(id) { hideLiveSports(it) }
                now += id
                prefs.edit().putStringSet(KEY_DONE, encode(now)).apply()
            }
        }
    }
}
