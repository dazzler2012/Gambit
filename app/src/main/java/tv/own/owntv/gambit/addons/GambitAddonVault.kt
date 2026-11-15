package tv.own.owntv.gambit.addons

import android.content.Context
import tv.own.owntv.gambit.addons.data.AddonVault

/**
 * The saved addon list, encrypted with [GambitSecretStore] in Gambit's own preferences file. Android
 * backup and device transfer exclude it (allowBackup="false" and data_extraction_rules), and OwnTV's
 * Backup & Restore never reads it. If the key is gone (app data cleared), [read] returns null and the
 * list starts empty; the addons have to be added again.
 */
class GambitAddonVault(context: Context, private val secrets: GambitSecretStore) : AddonVault {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)?.let(secrets::decrypt)

    /** If the Keystore fails, nothing is written (the list stays as it is in memory) rather than crash. */
    override fun write(text: String) {
        val sealed = runCatching { secrets.encrypt(text) }.getOrNull() ?: return
        prefs.edit().putString(KEY, sealed).apply()
    }

    private companion object {
        const val PREFS = "gambit_addons"
        const val KEY = "addons_v1"
    }
}
