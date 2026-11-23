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

/**
 * What a bulk import did, as counts only (no names or addresses). [heldForKids] counts addons left out
 * because one of their catalogues has the key of a catalogue a parent approved for a kids profile, so
 * adding them would have put them on that profile without anyone approving it.
 */
data class AddonImportReport(
    val added: Int = 0,
    val alreadyAdded: Int = 0,
    val failed: Int = 0,
    val heldForKids: Int = 0,
)

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
) {
    private val saved: List<InstalledAddon>? = vault.read()?.let(AddonStoreCodec::decode)
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
     * addon with the same address in place, keeping its on/off state.
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
     * Adds [urls] in the given order after every addon already in the list, so the first addon (the
     * metadata one) stays first. Each is checked like [add], but an addon already in the list (same
     * address) is never fetched, replaced or moved. An addon is held back when any of its catalogue
     * keys is in [kidsApproved], so an import can never make a catalogue appear on a kids profile.
     * The list is saved once, at the end.
     */
    suspend fun importAll(urls: List<AddonUrl>, kidsApproved: Set<String>): AddonImportReport {
        val fresh = mutableListOf<InstalledAddon>()
        var already = 0
        var failed = 0
        var held = 0
        for (url in urls.distinct()) {
            if (_registry.value.all.any { it.url == url }) {
                already++
                continue
            }
            when (val result = repository.install(url.manifestUrl)) {
                is AddonResult.Err -> failed++
                is AddonResult.Ok -> {
                    val addon = result.value
                    val keys = addon.manifest.catalogs.map { CatalogRef(addon, it).key }
                    if (keys.any { it in kidsApproved }) held++ else fresh += addon
                }
            }
        }
        var added = 0
        update { registry ->
            fresh.fold(registry) { next, addon ->
                if (next.all.any { it.url == addon.url }) next else next.withAdded(addon).also { added++ }
            }
        }
        return AddonImportReport(added, already + fresh.size - added, failed, held)
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
