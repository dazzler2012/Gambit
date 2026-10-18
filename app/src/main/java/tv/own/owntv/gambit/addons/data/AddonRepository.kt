package tv.own.owntv.gambit.addons.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tv.own.owntv.gambit.addons.model.AddonResources
import tv.own.owntv.gambit.addons.model.AddonStream
import tv.own.owntv.gambit.addons.model.MetaDetail
import tv.own.owntv.gambit.addons.model.MetaPreview
import tv.own.owntv.gambit.addons.net.AddonClient
import tv.own.owntv.gambit.addons.net.AddonError
import tv.own.owntv.gambit.addons.net.AddonResult
import tv.own.owntv.gambit.addons.net.AddonUrl
import tv.own.owntv.gambit.addons.net.CatalogExtraArgs
import tv.own.owntv.gambit.addons.net.map

/**
 * Asks the right addons for content. Stateless: the caller passes the current [AddonRegistry], so
 * storage, profiles and UI stay outside this class.
 */
class AddonRepository(private val client: AddonClient) {

    /** Normalizes [rawUrl] and fetches its manifest. The result isn't added to any registry. */
    suspend fun install(rawUrl: String): AddonResult<InstalledAddon> {
        val url = AddonUrl.parse(rawUrl) ?: return AddonResult.Err(AddonError.InvalidUrl)
        return client.manifest(url).map { InstalledAddon(url, it) }
    }

    /** Fetches [addon]'s manifest again, keeping its URL and enabled state. */
    suspend fun refresh(addon: InstalledAddon): AddonResult<InstalledAddon> =
        client.manifest(addon.url, addon.label).map { addon.copy(manifest = it) }

    suspend fun catalog(ref: CatalogRef, extra: CatalogExtraArgs = CatalogExtraArgs()): AddonResult<List<MetaPreview>> =
        client.catalog(ref.addon.url, ref.catalog.type, ref.catalog.id, extra, ref.addon.label)

    /**
     * Meta from the first enabled addon that serves this type and id. If that addon fails or has
     * nothing, the next matching addon is asked; when all fail, the first addon's error is returned.
     */
    suspend fun meta(registry: AddonRegistry, type: String, id: String): AddonResult<MetaDetail> {
        val candidates = registry.metaAddons(type, id)
        if (candidates.isEmpty()) return AddonResult.Err(AddonError.NoAddon(AddonResources.META, type))
        var firstError: AddonResult.Err? = null
        for (addon in candidates) {
            when (val result = client.meta(addon.url, type, id, addon.label)) {
                is AddonResult.Ok -> return result
                is AddonResult.Err -> if (firstError == null) firstError = result
            }
        }
        return firstError!!
    }

    /**
     * Streams from every enabled addon that serves this type and video id, asked in parallel.
     * Results keep addon order. A failing addon doesn't hide the others: its error is in [StreamResults.errors].
     */
    suspend fun streams(registry: AddonRegistry, type: String, videoId: String): StreamResults {
        val candidates = registry.streamAddons(type, videoId)
        if (candidates.isEmpty()) return StreamResults(errors = listOf(AddonError.NoAddon(AddonResources.STREAM, type)))
        val results = coroutineScope {
            candidates.map { addon -> async { addon to client.streams(addon.url, type, videoId, addon.label) } }.awaitAll()
        }
        val sourced = mutableListOf<SourcedStream>()
        val errors = mutableListOf<AddonError>()
        for ((addon, result) in results) {
            when (result) {
                is AddonResult.Ok -> result.value.mapTo(sourced) { SourcedStream(addon.manifest.id, addon.manifest.name, it) }
                is AddonResult.Err -> errors += result.error
            }
        }
        val (playable, unsupported) = sourced.partition { it.stream.isPlayable }
        return StreamResults(playable, unsupported, errors)
    }
}

/** A stream and the addon it came from. */
data class SourcedStream(val addonId: String, val addonName: String, val stream: AddonStream)

/**
 * Streams split by whether Gambit can play them. [unsupported] holds torrent-only, YouTube, external
 * and other non-http results, so the UI can say why a title has nothing to play.
 */
data class StreamResults(
    val playable: List<SourcedStream> = emptyList(),
    val unsupported: List<SourcedStream> = emptyList(),
    val errors: List<AddonError> = emptyList(),
)
