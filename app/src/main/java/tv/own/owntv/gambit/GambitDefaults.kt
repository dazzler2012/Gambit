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
 * Gambit's look out of the box, applied once per install right after core's Stage defaults: a dark
 * green accent (white text reads on it), Glass off, the plain background, and a wider channel column
 * in the TV Guide. Each value is written only while it is still OwnTV's default, so a choice the user
 * has made is kept. Everything stays changeable in Settings afterwards.
 *
 * Whether it has run is kept in Gambit's own preferences file, never in core's settings.
 */
object GambitDefaults {
    private const val TAG = "Gambit"
    private const val PREFS = "gambit"
    private const val KEY_APPLIED = "defaults_v1"

    const val ACCENT_HEX = "#2E5A2E"
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
