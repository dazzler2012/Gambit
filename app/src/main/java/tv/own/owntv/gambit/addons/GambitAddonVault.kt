package tv.own.owntv.gambit.addons

import android.content.Context
import tv.own.owntv.gambit.addons.data.AddonVault

/**
 * The saved addon list, encrypted with [GambitSecretStore] in Gambit's own preferences file. Android
 * backup and device transfer exclude it (allowBackup="false" and data_extraction_rules), and OwnTV's
 * Backup & Restore never reads it. If the key is gone or the data is damaged, [read] returns null while
 * [hasSaved] stays true, so the screen can say the addons need adding again.
 */
class GambitAddonVault(context: Context, private val secrets: GambitSecretStore) : AddonVault {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun hasSaved(): Boolean = prefs.contains(KEY)

    override fun read(): String? = prefs.getString(KEY, null)?.let(secrets::decrypt)

    /** If the Keystore fails, nothing is written (the list stays as it is in memory) rather than crash. */
    override fun write(text: String): Boolean {
        val sealed = runCatching { secrets.encrypt(text) }.getOrNull() ?: return false
        prefs.edit().putString(KEY, sealed).apply()
        return true
    }

    private companion object {
        const val PREFS = "gambit_addons"
        const val KEY = "addons_v1"
    }
}
