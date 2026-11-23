package tv.own.owntv.gambit.addons

import android.content.Context
import tv.own.owntv.gambit.addons.data.KidsAccessVault

/**
 * The kids approvals, encrypted with [GambitSecretStore] in Gambit's own preferences file next to the
 * addon list. Writes and removals use commit(), not apply(), so they are on disk when they return. If the
 * key is gone or the data is damaged, [read] returns null and nobody has approvals.
 */
class GambitKidsAccessVault(context: Context, private val secrets: GambitSecretStore) : KidsAccessVault {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun hasSaved(): Boolean = prefs.contains(KEY)

    override fun read(): String? = runCatching { prefs.getString(KEY, null)?.let(secrets::decrypt) }.getOrNull()

    override fun write(text: String): Boolean {
        val sealed = runCatching { secrets.encrypt(text) }.getOrNull() ?: return false
        return prefs.edit().putString(KEY, sealed).commit()
    }

    override fun remove(): Boolean = prefs.edit().remove(KEY).commit()

    private companion object {
        const val PREFS = "gambit_addons"
        const val KEY = "kids_access_v1"
    }
}
