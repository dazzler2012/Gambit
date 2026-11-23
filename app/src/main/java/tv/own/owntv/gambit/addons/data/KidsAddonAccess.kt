package tv.own.owntv.gambit.addons.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where the kids approvals live. The text holds catalogue keys ([CatalogKeys]: a random setup id, type and
 * catalogue id; never an addon address, host, path or key), but it is still kept encrypted and on this
 * device only (see GambitKidsAccessVault). Writes must be durable when they return.
 */
interface KidsAccessVault {
    /** True when something is saved, whether or not it can still be opened. */
    fun hasSaved(): Boolean

    /** The saved text, or null when there is none or it can't be opened. */
    fun read(): String?

    /** Saves [text] and returns only once it is on disk; false when that failed. */
    fun write(text: String): Boolean

    /** Deletes what is saved and returns only once that is on disk; false when it failed. */
    fun remove(): Boolean
}

/**
 * The saved form: kids profile id → approved catalogue keys. Pure: no Android, no I/O. Version 2 holds
 * [CatalogKeys]; anything saved before (version 1, keyed by manifest id and host) reads as unreadable, so
 * those approvals are gone (fails closed).
 */
internal object KidsAccessCodec {
    private const val VERSION = 2

    fun encode(approvals: Map<Long, Set<String>>): String = JSONObject()
        .put("v", VERSION)
        .put("profiles", JSONObject().apply {
            approvals.forEach { (id, keys) -> if (keys.isNotEmpty()) put(id.toString(), JSONArray(keys.sorted())) }
        })
        .toString()

    /** The saved approvals, plus whether anything saved was left out (an unreadable entry or key). */
    class Decoded(val approvals: Map<Long, Set<String>>, val dropped: Boolean)

    /**
     * The saved approvals, or null when [text] can't be read at all (including another version). Unreadable
     * entries and keys that aren't valid [CatalogKeys] are dropped.
     */
    fun decode(text: String): Map<Long, Set<String>>? = decodeAll(text)?.approvals

    /** Like [decode], and says whether anything was dropped (the caller then saves the rest again). */
    fun decodeAll(text: String): Decoded? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optInt("v") != VERSION) return null
        val profiles = root.optJSONObject("profiles") ?: return null
        val out = LinkedHashMap<Long, Set<String>>()
        var dropped = false
        for (name in profiles.keys()) {
            val id = name.toLongOrNull()
            val array = profiles.optJSONArray(name)
            if (id == null || array == null) { dropped = true; continue }
            val all = (0 until array.length()).map { array.optString(it) }
            val keys = all.filter(CatalogKeys::isValid).toSet()
            if (keys.size != all.size) dropped = true
            if (keys.isNotEmpty()) out[id] = keys
        }
        return Decoded(out, dropped)
    }
}

/** The setup ids of every addon in this registry, on or off. Kids approvals for any other setup are pruned. */
fun AddonRegistry.setupIds(): Set<String> = all.mapTo(HashSet()) { it.setupId }

/**
 * Per kids profile, the catalogues a parent approved. Memory is the truth the rest of the app reads; the
 * vault is its copy. Fails closed: a vault that can't be read gives no approvals. Nothing here logs.
 *
 * Writes go to storage on [io], one at a time, and each saves the state as it is when its turn comes.
 * A write that fails leaves [dirty] set so the watcher ([KidsAccessPruner]) can try again. Saved text that
 * opens but is out of date (an older version, or keys that are no longer valid) also starts [dirty], so
 * the watcher's first pass replaces it with what is left.
 */
class KidsAddonAccess(
    private val vault: KidsAccessVault,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private class Loaded(val approvals: Map<Long, Set<String>>, val stale: Boolean)

    private val loaded = load()
    private val _approvals = MutableStateFlow(loaded.approvals)
    val approvals: StateFlow<Map<Long, Set<String>>> = _approvals.asStateFlow()

    private val writeLock = Mutex()

    @Volatile private var dirty = loaded.stale

    // Runs when the app starts, so it must never throw: any failure reading the vault means no approvals.
    // Text that opened but could not be decoded, or lost some keys, is stale: it is rewritten (or removed).
    private fun load(): Loaded = try {
        val text = if (vault.hasSaved()) vault.read() else null
        if (text == null) Loaded(emptyMap(), stale = false) else {
            val decoded = KidsAccessCodec.decodeAll(text)
            if (decoded == null) Loaded(emptyMap(), stale = true) else Loaded(decoded.approvals, decoded.dropped)
        }
    } catch (e: Exception) {
        Loaded(emptyMap(), stale = false)
    }

    /** The catalogue keys approved for [profileId]; empty when none. */
    fun approved(profileId: Long): Set<String> = _approvals.value[profileId].orEmpty()

    /** The profiles that have an entry now. */
    fun profileIds(): Set<Long> = _approvals.value.keys

    /**
     * Approves exactly [keys] for [profileId] (empty removes the entry). Keys that aren't valid [CatalogKeys]
     * are ignored. False when it could not be saved.
     */
    suspend fun set(profileId: Long, keys: Set<String>): Boolean {
        val valid = keys.filterTo(HashSet(), CatalogKeys::isValid)
        _approvals.update { if (valid.isEmpty()) it - profileId else it + (profileId to valid) }
        return persist()
    }

    /**
     * Keeps, on every kids profile, only the approvals whose addon setup is in [setupIds] (memory first, then
     * storage), so a removed addon's approvals are gone. True when nothing changed and nothing was waiting,
     * or the write succeeded.
     */
    suspend fun retainSetups(setupIds: Set<String>): Boolean {
        var changed = false
        _approvals.update { current ->
            changed = false
            val next = LinkedHashMap<Long, Set<String>>()
            current.forEach { (id, keys) ->
                val kept = keys.filterTo(HashSet()) { CatalogKeys.setupOf(it) in setupIds }
                if (kept.size != keys.size) changed = true
                if (kept.isNotEmpty()) next[id] = kept
            }
            if (changed) next else current
        }
        return if (changed || dirty) persist() else true
    }

    /**
     * Removes [profileId]'s entry: memory first, then storage, and returns the storage result. If storage
     * fails the entry is still gone from memory and the watcher retries.
     */
    suspend fun clearNow(profileId: Long): Boolean {
        val had = profileId in _approvals.value
        _approvals.update { it - profileId }
        if (!had && !dirty) return true
        return persist()
    }

    /** Tries a failed write again. True when nothing was waiting or the write now succeeded. */
    suspend fun flushIfDirty(): Boolean = if (dirty) persist() else true

    private suspend fun persist(): Boolean = writeLock.withLock {
        withContext(io) {
            dirty = true // stays set if this is cancelled before it finishes
            val snapshot = _approvals.value
            val ok = if (snapshot.isEmpty()) vault.remove() else vault.write(KidsAccessCodec.encode(snapshot))
            dirty = !ok
            ok
        }
    }
}

/**
 * The backstop for entries that outlive their kids profile or their addon: on every change of the profile
 * list, clears any entry whose profile is gone or no longer a kids profile; on every change of the addon
 * list, drops approvals of addon setups that are no longer installed. Both retry a failed write.
 *
 * [setupIds] must give the addon list as loaded, never a placeholder before loading. AddonStore loads its
 * list in its constructor, so its registry's first value is already the loaded list; a list that could not
 * be read is empty, and clearing every approval then is right (its addons come back as new setups).
 */
class KidsAccessPruner(
    private val access: KidsAddonAccess,
    private val kidsProfileIds: Flow<Set<Long>>,
    private val scope: CoroutineScope,
    private val setupIds: Flow<Set<String>> = emptyFlow(),
) {
    fun start(): Job = scope.launch {
        launch { kidsProfileIds.collect { prune(it) } }
        launch { setupIds.collect { pruneSetups(it) } }
    }

    suspend fun prune(kidsIds: Set<Long>) {
        access.profileIds().filterNot { it in kidsIds }.forEach { access.clearNow(it) }
        access.flushIfDirty()
    }

    suspend fun pruneSetups(installed: Set<String>) {
        access.retainSetups(installed)
        access.flushIfDirty()
    }
}
