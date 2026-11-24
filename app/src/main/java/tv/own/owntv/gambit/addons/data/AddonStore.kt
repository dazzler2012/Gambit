package tv.own.owntv.gambit.addons.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.own.owntv.gambit.addons.net.AddonError
import tv.own.owntv.gambit.addons.net.AddonResult
import tv.own.owntv.gambit.addons.net.AddonUrl

/** Why an addon could not be added. No case carries the address. */
enum class AddonAddError { NOT_AN_ADDRESS, UNREACHABLE, NOT_AN_ADDON }

sealed interface AddonAddResult {
    data class Added(val name: String) : AddonAddResult
    data class Failed(val error: AddonAddError) : AddonAddResult
}

/** One addon to import: its address and the name to call it by if it fails (null when unknown). */
data class AddonImportRequest(val url: AddonUrl, val name: String?) {
    override fun toString(): String = "AddonImportRequest($name, ${url.host})"
}

/** Why one imported addon couldn't be loaded. No case carries the address or a response. */
sealed interface AddonImportFailReason {
    /** No connection, or no answer in time. */
    data object Unreachable : AddonImportFailReason

    /** The server answered with an HTTP status other than 2xx. */
    data class Refused(val httpCode: Int) : AddonImportFailReason

    /** The server answered, but not with a Stremio addon manifest. */
    data object NotAnAddon : AddonImportFailReason
}

/** An addon that couldn't be loaded, by name only. */
data class AddonImportFailure(val name: String?, val reason: AddonImportFailReason)

/**
 * What a bulk import did: counts, plus each addon that couldn't be loaded by name and reason (never an
 * address).
 */
data class AddonImportReport(
    val added: Int = 0,
    val alreadyAdded: Int = 0,
    val failures: List<AddonImportFailure> = emptyList(),
) {
    val failed: Int get() = failures.size
}

/** The import's wording of an addon error: unreachable, refused with its status, or not an addon. */
fun AddonError.toImportFailReason(): AddonImportFailReason = when (this) {
    is AddonError.Timeout, is AddonError.Network -> AddonImportFailReason.Unreachable
    is AddonError.HttpStatus -> AddonImportFailReason.Refused(code)
    AddonError.InvalidUrl, is AddonError.MalformedJson, is AddonError.MissingField, is AddonError.NotFound, is AddonError.NoAddon ->
        AddonImportFailReason.NotAnAddon
}

/**
 * What the add screens can say about a failed install. A 4xx means the server answered but has no
 * addon at that address; a 5xx, like a timeout or a network error, means it couldn't be reached.
 */
fun AddonError.toAddError(): AddonAddError = when (this) {
    AddonError.InvalidUrl -> AddonAddError.NOT_AN_ADDRESS
    is AddonError.HttpStatus -> if (code in 400..499) AddonAddError.NOT_AN_ADDON else AddonAddError.UNREACHABLE
    is AddonError.Timeout, is AddonError.Network -> AddonAddError.UNREACHABLE
    is AddonError.MalformedJson, is AddonError.MissingField, is AddonError.NotFound, is AddonError.NoAddon -> AddonAddError.NOT_AN_ADDON
}

/**
 * Where the saved addon list lives. It holds addon addresses (the user's keys), so an implementation
 * must keep it encrypted and on this device only (see GambitAddonVault).
 */
interface AddonVault {
    /** True when a list was saved, whether or not it can still be opened. */
    fun hasSaved(): Boolean

    /** The saved text, or null when there is none or it can't be opened (for example after the key was lost). */
    fun read(): String?

    /** Saves [text]; false when it could not be saved. */
    fun write(text: String): Boolean
}

/**
 * The device's addons, shared by its adult profiles, in order: an [AddonRegistry] that is saved on every
 * change. Adding goes through [AddonRepository.install], which checks the manifest first. Nothing here
 * logs, and no result carries the address.
 */
class AddonStore(
    private val repository: AddonRepository,
    private val vault: AddonVault,
    mintSetupId: () -> String = AddonSetupId::mint,
) {
    private val decoded: AddonStoreCodec.Decoded? = vault.read()?.let { AddonStoreCodec.decodeAll(it, mintSetupId) }
    private val saved: List<InstalledAddon>? = decoded?.addons

    init {
        // Setup ids given at load (a list saved before they existed, or a damaged id) are saved at once, so
        // they are the same after a restart and kids approvals tied to them keep matching.
        if (decoded != null && decoded.minted) vault.write(AddonStoreCodec.encode(decoded.addons))
    }

    private val _registry = MutableStateFlow(AddonRegistry(saved.orEmpty()))
    val registry: StateFlow<AddonRegistry> = _registry.asStateFlow()

    private val _unreadable = MutableStateFlow(saved == null && vault.hasSaved())
    /**
     * True when a saved list exists but could not be read (the key was lost, or the data is damaged), so
     * the addons need adding again. Goes back to false once a new list is saved.
     */
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    /**
     * Checks [raw] is an addon (its manifest loads and parses), then adds it at the end, or replaces the
     * addon with the same address in place, keeping its on/off state. Either way it is a new setup (see
     * [AddonSetupId]).
     */
    suspend fun add(raw: String): AddonAddResult =
        when (val result = repository.install(raw)) {
            is AddonResult.Err -> AddonAddResult.Failed(result.error.toAddError())
            is AddonResult.Ok -> {
                val fresh = result.value
                update { registry ->
                    val kept = registry.all.firstOrNull { it.url == fresh.url }?.enabled ?: true
                    registry.withAdded(fresh.copy(enabled = kept))
                }
                AddonAddResult.Added(fresh.manifest.name)
            }
        }

    /**
     * Adds [requests] in the given order after every addon already in the list, so the first addon (the
     * metadata one) stays first. Each is checked like [add], but an addon already in the list is never
     * fetched, replaced or moved. "Already in the list" compares addresses as they are sent
     * ([AddonUrl.canonical]), so `|` and `%7C` count as the same addon. Every imported addon is a new
     * setup ([AddonSetupId]), so it starts unapproved on every kids profile. Each one that can't be loaded
     * is reported by name and reason. The list is saved once, at the end.
     */
    suspend fun importAll(requests: List<AddonImportRequest>): AddonImportReport {
        val fresh = mutableListOf<InstalledAddon>()
        val failures = mutableListOf<AddonImportFailure>()
        val seen = mutableSetOf<String>()
        var already = 0
        for (request in requests) {
            val canonical = request.url.canonical
            if (!seen.add(canonical)) continue
            if (_registry.value.all.any { it.url.canonical == canonical }) {
                already++
                continue
            }
            when (val result = repository.install(request.url.manifestUrl)) {
                is AddonResult.Err -> failures += AddonImportFailure(request.name, result.error.toImportFailReason())
                is AddonResult.Ok -> fresh += result.value
            }
        }
        var added = 0
        update { registry ->
            fresh.fold(registry) { next, addon ->
                if (next.all.any { it.url.sameAddonAs(addon.url) }) next else next.withAdded(addon).also { added++ }
            }
        }
        return AddonImportReport(added, already + fresh.size - added, failures)
    }

    fun remove(url: AddonUrl) = update { it.withRemoved(url) }

    fun setEnabled(url: AddonUrl, on: Boolean) = update { it.withEnabled(url, on) }

    /** Moves the addon one place up ([delta] = −1) or down (+1). */
    fun move(url: AddonUrl, delta: Int) = update { registry ->
        val from = registry.all.indexOfFirst { it.url == url }
        if (from < 0) registry else registry.moved(url, from + delta)
    }

    private fun update(change: (AddonRegistry) -> AddonRegistry) {
        synchronized(this) {
            val next = change(_registry.value)
            if (next.all == _registry.value.all) return
            _registry.value = next
            if (vault.write(AddonStoreCodec.encode(next.all))) _unreadable.value = false
        }
    }
}
