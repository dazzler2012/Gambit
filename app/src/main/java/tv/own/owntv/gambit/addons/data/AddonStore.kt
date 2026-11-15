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

/** What the add screens can say about a failed install. */
fun AddonError.toAddError(): AddonAddError = when (this) {
    AddonError.InvalidUrl -> AddonAddError.NOT_AN_ADDRESS
    is AddonError.Timeout, is AddonError.Network, is AddonError.HttpStatus -> AddonAddError.UNREACHABLE
    is AddonError.MalformedJson, is AddonError.MissingField, is AddonError.NotFound, is AddonError.NoAddon -> AddonAddError.NOT_AN_ADDON
}

/**
 * Where the saved addon list lives. It holds addon addresses (the user's keys), so an implementation
 * must keep it encrypted and on this device only (see GambitAddonVault). [read] returns null when there
 * is nothing saved or it can't be opened (for example after the key was lost).
 */
interface AddonVault {
    fun read(): String?
    fun write(text: String)
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
    private val _registry = MutableStateFlow(AddonRegistry(load()))
    val registry: StateFlow<AddonRegistry> = _registry.asStateFlow()

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
            vault.write(AddonStoreCodec.encode(next.all))
        }
    }

    private fun load(): List<InstalledAddon> = vault.read()?.let(AddonStoreCodec::decode) ?: emptyList()
}
