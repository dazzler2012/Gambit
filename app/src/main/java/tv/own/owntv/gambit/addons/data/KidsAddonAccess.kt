package tv.own.owntv.gambit.addons.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where the kids approvals live. The text holds catalogue keys (manifest id, host, type, catalogue id:
 * never an addon path or key), but it is still kept encrypted and on this device only (see
 * GambitKidsAccessVault). Writes must be durable when they return.
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

/** The saved form: kids profile id → approved catalogue keys. Pure: no Android, no I/O. */
internal object KidsAccessCodec {
    private const val VERSION = 1

    fun encode(approvals: Map<Long, Set<String>>): String = JSONObject()
        .put("v", VERSION)
        .put("profiles", JSONObject().apply {
            approvals.forEach { (id, keys) -> if (keys.isNotEmpty()) put(id.toString(), JSONArray(keys.sorted())) }
        })
        .toString()

    /** The saved approvals, or null when [text] can't be read at all. Unreadable entries are dropped. */
    fun decode(text: String): Map<Long, Set<String>>? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optInt("v") != VERSION) return null
        val profiles = root.optJSONObject("profiles") ?: return null
        val out = LinkedHashMap<Long, Set<String>>()
        for (name in profiles.keys()) {
            val id = name.toLongOrNull() ?: continue
            val array = profiles.optJSONArray(name) ?: continue
            val keys = (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }.toSet()
            if (keys.isNotEmpty()) out[id] = keys
        }
        return out
    }
}

/**
 * Per kids profile, the catalogues a parent approved. Memory is the truth the rest of the app reads; the
 * vault is its copy. Fails closed: a vault that can't be read gives no approvals. Nothing here logs.
 *
 * Writes go to storage on [io], one at a time, and each saves the state as it is when its turn comes.
 * A write that fails leaves [dirty] set so the watcher ([KidsAccessPruner]) can try again.
 */
class KidsAddonAccess(
    private val vault: KidsAccessVault,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _approvals = MutableStateFlow(load())
    val approvals: StateFlow<Map<Long, Set<String>>> = _approvals.asStateFlow()

    private val writeLock = Mutex()

    @Volatile private var dirty = false

    // Runs when the app starts, so it must never throw: any failure reading the vault means no approvals.
    private fun load(): Map<Long, Set<String>> = try {
        if (!vault.hasSaved()) emptyMap() else vault.read()?.let(KidsAccessCodec::decode).orEmpty()
    } catch (e: Exception) {
        emptyMap()
    }

    /** The catalogue keys approved for [profileId]; empty when none. */
    fun approved(profileId: Long): Set<String> = _approvals.value[profileId].orEmpty()

    /** The profiles that have an entry now. */
    fun profileIds(): Set<Long> = _approvals.value.keys

    /** Approves exactly [keys] for [profileId] (empty removes the entry). False when it could not be saved. */
    suspend fun set(profileId: Long, keys: Set<String>): Boolean {
        _approvals.update { if (keys.isEmpty()) it - profileId else it + (profileId to keys.toSet()) }
        return persist()
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
 * The backstop for entries that outlive their kids profile: on every change of the profile list, clears any
 * entry whose profile is gone or no longer a kids profile, and retries a failed write.
 */
class KidsAccessPruner(
    private val access: KidsAddonAccess,
    private val kidsProfileIds: Flow<Set<Long>>,
    private val scope: CoroutineScope,
) {
    fun start(): Job = scope.launch { kidsProfileIds.collect { prune(it) } }

    suspend fun prune(kidsIds: Set<Long>) {
        access.profileIds().filterNot { it in kidsIds }.forEach { access.clearNow(it) }
        access.flushIfDirty()
    }
}
