package tv.own.owntv.gambit.addons

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One installed addon as the device keeps it. Its address is only ever held encrypted ([sealedUrl]). */
data class InstalledAddon(
    val id: String,
    val name: String,
    val version: String,
    val host: String,
    val types: List<String>,
    val resources: List<String>,
    val catalogCount: Int,
    val enabled: Boolean,
    internal val sealedUrl: String,
)

/** Why an addon could not be added. No message carries the address. */
enum class AddonAddError { NOT_AN_ADDRESS, UNREACHABLE, NOT_AN_ADDON }

sealed interface AddonAddResult {
    data class Added(val name: String) : AddonAddResult
    data class Failed(val error: AddonAddError) : AddonAddResult
}

/**
 * The device's addon list, shared by every adult profile on it, in order (the first addon that serves a
 * resource is asked first). Kept in Gambit's own preferences file `gambit_addons`, which Android's backup
 * and device transfer exclude and OwnTV's Backup & Restore never reads; the addresses are encrypted.
 * Nothing here logs.
 */
class AddonStore(
    context: Context,
    private val secrets: GambitSecretStore,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val _addons = MutableStateFlow(load())
    val addons: StateFlow<List<InstalledAddon>> = _addons.asStateFlow()

    /** Check [raw] is an addon (its manifest loads and parses), then add it, or replace the same addon. */
    suspend fun add(raw: String): AddonAddResult {
        val base = AddonUrls.normalize(raw) ?: return AddonAddResult.Failed(AddonAddError.NOT_AN_ADDRESS)
        val host = AddonUrls.hostOf(base) ?: return AddonAddResult.Failed(AddonAddError.NOT_AN_ADDRESS)
        val body = fetch(AddonUrls.manifestUrl(base)) ?: return AddonAddResult.Failed(AddonAddError.UNREACHABLE)
        val m = AddonManifest.parse(body) ?: return AddonAddResult.Failed(AddonAddError.NOT_AN_ADDON)
        val sealed = withContext(Dispatchers.IO) { secrets.encrypt(base) }
        val addon = InstalledAddon(m.id, m.name, m.version, host, m.types, m.resources, m.catalogCount, enabled = true, sealedUrl = sealed)
        update { list ->
            val at = list.indexOfFirst { it.id == m.id }
            if (at >= 0) list.toMutableList().also { it[at] = addon.copy(enabled = list[at].enabled) } else list + addon
        }
        return AddonAddResult.Added(m.name)
    }

    fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    fun setEnabled(id: String, on: Boolean) = update { list -> list.map { if (it.id == id) it.copy(enabled = on) else it } }

    /** Move [id] one place up (−1) or down (+1). */
    fun move(id: String, delta: Int) = update { list ->
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) list else list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** The addon's address, for the code that calls it (Stage 2 on). Never for display or logs. */
    fun urlOf(id: String): String? = _addons.value.firstOrNull { it.id == id }?.let { secrets.decrypt(it.sealedUrl) }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body.string().takeIf { it.length <= MAX_MANIFEST }
            }
        }.getOrNull()
    }

    private fun update(change: (List<InstalledAddon>) -> List<InstalledAddon>) {
        synchronized(this) {
            val next = change(_addons.value)
            _addons.value = next
            prefs.edit().putString(KEY, encode(next)).apply()
        }
    }

    private fun load(): List<InstalledAddon> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            InstalledAddon(
                id = o.getString(F_ID), name = o.getString(F_NAME), version = o.optString(F_VERSION),
                host = o.optString(F_HOST), types = o.optJSONArray(F_TYPES).list(), resources = o.optJSONArray(F_RESOURCES).list(),
                catalogCount = o.optInt(F_CATALOGS), enabled = o.optBoolean(F_ENABLED, true), sealedUrl = o.getString(F_SEALED),
            )
        }
    }.getOrDefault(emptyList())

    private fun encode(list: List<InstalledAddon>): String = JSONArray().apply {
        list.forEach { a ->
            put(
                JSONObject()
                    .put(F_ID, a.id).put(F_NAME, a.name).put(F_VERSION, a.version).put(F_HOST, a.host)
                    .put(F_TYPES, JSONArray(a.types)).put(F_RESOURCES, JSONArray(a.resources))
                    .put(F_CATALOGS, a.catalogCount).put(F_ENABLED, a.enabled).put(F_SEALED, a.sealedUrl),
            )
        }
    }.toString()

    private fun JSONArray?.list(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private companion object {
        const val PREFS = "gambit_addons"
        const val KEY = "addons_v1"
        const val MAX_MANIFEST = 1_000_000
        const val F_ID = "id"
        const val F_NAME = "name"
        const val F_VERSION = "version"
        const val F_HOST = "host"
        const val F_TYPES = "types"
        const val F_RESOURCES = "resources"
        const val F_CATALOGS = "catalogs"
        const val F_ENABLED = "enabled"
        const val F_SEALED = "sealed"
    }
}
