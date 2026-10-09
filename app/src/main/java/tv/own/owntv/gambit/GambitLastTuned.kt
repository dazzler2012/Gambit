package tv.own.owntv.gambit

import android.content.Context

/**
 * The channel last tuned to play, kept across restarts for the TV Guide's ▶. Core's "last live channel"
 * cannot serve: it follows the focused channel (browsing moves it), and it is only restored with the
 * Browsing-lists "Remember last item" setting on. Kept in Gambit's own preferences file.
 */
object GambitLastTuned {
    private const val PREFS = "gambit"
    private const val KEY = "last_tuned_channel_v1"

    /** The saved channel id, or null before anything has been played. Reads from disk: call off the main thread. */
    fun read(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, -1L).takeIf { it > 0 }

    fun write(context: Context, channelId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, channelId).apply()
    }
}
