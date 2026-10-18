package tv.own.owntv.gambit

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.settings.GuideWidthShares
import tv.own.owntv.core.settings.GuideWidthLimits
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.BackgroundStyle

/**
 * Gambit's look out of the box, applied once per install right after core's Stage defaults: a light
 * green accent (readable as text; focus fills use a darker shade of it), Glass off, the plain
 * background, and a wider channel column in the TV Guide. Each value is written only while it is still OwnTV's default, so a choice the user
 * has made is kept. Everything stays changeable in Settings afterwards.
 *
 * Whether it has run is kept in Gambit's own preferences file, never in core's settings.
 */
object GambitDefaults {
    private const val TAG = "Gambit"
    private const val PREFS = "gambit"
    private const val KEY_APPLIED = "defaults_v1"
    private const val KEY_ACCENT_V2 = "accent_v2"
    private const val KEY_UPDATE_CHECK_OFF = "update_check_off_v1"
    private const val KEY_NAV = "nav_v1"

    /**
     * OwnTV's check for a new version at startup looks at OwnTV's releases, so Gambit never runs it — on
     * every profile, whatever that profile's own setting says (the setting is per profile, and the one-time
     * default below only reached the profile active when it ran). Read by OwnTVShell.
     */
    const val STARTUP_UPDATE_CHECK = false

    /** Light green: readable as text on black. Focus fills darken it (see GambitTokens). */
    const val ACCENT_HEX = "#66BB6A"
    /** The dark green the first Gambit builds set; moved to [ACCENT_HEX] once, by [apply]. */
    private const val OLD_ACCENT_HEX = "#2E5A2E"
    /** Channel column share of the guide, in percent of the two columns. */
    const val GUIDE_CHANNEL_SHARE = 26

    /** Applies the defaults once per install. A failure is logged as a warning and retried on the next start. */
    suspend fun applyOnce(context: Context, settings: SettingsRepository) {
        try {
            apply(context, settings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gambit defaults not applied; will retry on next start", e)
        }
    }

    private suspend fun apply(context: Context, settings: SettingsRepository) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACCENT_V2, false)) {
            if (settings.customAccent.first().equals(OLD_ACCENT_HEX, ignoreCase = true)) settings.setCustomAccent(ACCENT_HEX)
            prefs.edit().putBoolean(KEY_ACCENT_V2, true).apply()
        }
        // OwnTV's updater asks about OwnTV's releases, not Gambit's: stop it running at every start.
        // Once, so turning it back on in Settings sticks.
        if (!prefs.getBoolean(KEY_UPDATE_CHECK_OFF, false)) {
            settings.setUpdateCheckOnStart(false)
            prefs.edit().putBoolean(KEY_UPDATE_CHECK_OFF, true).apply()
        }
        // The side menu as TiviMate's: the icon strip at rest, opening over the content while it has
        // focus (Docked + Compact, Widen on focus = Normal), the screen's full height. Once, and only
        // where OwnTV's defaults are still in place.
        if (!prefs.getBoolean(KEY_NAV, false)) {
            if (settings.navWiden.first() == null) settings.setNavWiden(SettingsRepository.NavSize.NORMAL)
            if (settings.navLength.first() == SettingsRepository.NavLength.FIT) settings.setNavLength(SettingsRepository.NavLength.FULL)
            prefs.edit().putBoolean(KEY_NAV, true).apply()
        }
        if (prefs.getBoolean(KEY_APPLIED, false)) return
        if (settings.customAccent.first().isBlank()) settings.setCustomAccent(ACCENT_HEX)
        if (settings.glassConfig.first().enabled) settings.setGlassScopeBitmask(0)
        if (settings.backgroundConfig.first().style == BackgroundStyle.STAGE) settings.setBackgroundStyle(BackgroundStyle.PLAIN)
        if (!settings.guideWidthEnabled.first()) {
            settings.setGuideWidths(true, GuideWidthShares(GUIDE_CHANNEL_SHARE, GuideWidthLimits.TOTAL - GUIDE_CHANNEL_SHARE))
        }
        prefs.edit().putBoolean(KEY_APPLIED, true).apply()
    }
}
