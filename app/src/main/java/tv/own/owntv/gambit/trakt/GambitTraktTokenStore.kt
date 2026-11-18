package tv.own.owntv.gambit.trakt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.gambit.addons.GambitSecretStore

/** Where the encrypted Trakt sign-in text lives. Writes and clears report whether they reached storage. */
internal interface TraktTokenVault {
    fun read(): String?

    fun write(text: String): Boolean

    fun clear(): Boolean
}

/**
 * The Trakt sign-in on this TV: encrypted with [GambitSecretStore] (an AES-GCM key kept in the Android
 * Keystore) in Gambit's own preferences file, like the addon list. Android backup and device transfer
 * exclude it (allowBackup="false"), and OwnTV's Backup & Restore never reads it.
 *
 * Unlike the addon list, writes use `commit()`, not `apply()`: Trakt refresh tokens are single use, so
 * the new pair must be on disk before the old one is forgotten.
 */
internal class GambitTraktVault(context: Context, private val secrets: GambitSecretStore) : TraktTokenVault {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The plain text, or null when nothing is saved or it can't be opened (another device's data, a lost key). */
    override fun read(): String? = prefs.getString(KEY, null)?.let(secrets::decrypt)

    /** False when the Keystore or the disk write failed; nothing is changed then. */
    override fun write(text: String): Boolean {
        val sealed = runCatching { secrets.encrypt(text) }.getOrNull() ?: return false
        return prefs.edit().putString(KEY, sealed).commit()
    }

    override fun clear(): Boolean = prefs.edit().remove(KEY).commit()

    private companion object {
        const val PREFS = "gambit_trakt"
        const val KEY = "tokens_v1"
    }
}

/**
 * The [TraktTokenStore] the Trakt client reads and writes through. A saved sign-in that can't be opened
 * or read reads as "nobody is signed in". A write or clear that fails throws, so the client reports
 * [TraktError.Storage] instead of carrying on with tokens that were never saved. The exceptions carry
 * no message at all, so never a token.
 */
internal class GambitTraktTokenStore(private val vault: TraktTokenVault) : TraktTokenStore {
    override suspend fun read(): TraktTokens? = withContext(Dispatchers.IO) {
        vault.read()?.let(GambitTraktTokenCodec::decode)
    }

    override suspend fun write(tokens: TraktTokens) = withContext(Dispatchers.IO) {
        if (!vault.write(GambitTraktTokenCodec.encode(tokens))) throw IllegalStateException()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        if (!vault.clear()) throw IllegalStateException()
    }
}
