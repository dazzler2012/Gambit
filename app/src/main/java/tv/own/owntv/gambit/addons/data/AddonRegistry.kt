package tv.own.owntv.gambit.addons.data

import tv.own.owntv.gambit.addons.model.AddonCatalog
import tv.own.owntv.gambit.addons.model.AddonResources
import tv.own.owntv.gambit.addons.net.AddonUrl

/**
 * The user's addons in their chosen order. Order decides which addon answers first: meta comes from
 * the first enabled addon that serves it, so the metadata addon goes at the top.
 *
 * Immutable: every change returns a new registry, ready to be saved by whatever stores it.
 */
class AddonRegistry(
    val all: List<InstalledAddon> = emptyList(),
    /** When set, [browseCatalogs] lists only these catalogue keys (see [limitedTo]). */
    private val approvedCatalogs: Set<String>? = null,
) {

    val enabled: List<InstalledAddon> get() = all.filter { it.enabled }

    /**
     * The same addons, but [browseCatalogs] lists only the catalogues whose [CatalogRef.key] is in [keys],
     * in the usual order. Keys that match no installed catalogue are ignored. Meta and stream lookups are
     * unchanged. Limiting a limited registry keeps only the keys both allow.
     */
    fun limitedTo(keys: Set<String>): AddonRegistry =
        AddonRegistry(all, approvedCatalogs?.let { it intersect keys } ?: keys.toSet())

    /** Adds [addon] at the end, or replaces the one with the same URL in place (keeping its position). */
    fun withAdded(addon: InstalledAddon): AddonRegistry {
        val index = all.indexOfFirst { it.url == addon.url }
        return AddonRegistry(if (index < 0) all + addon else all.toMutableList().also { it[index] = addon }, approvedCatalogs)
    }

    fun withRemoved(url: AddonUrl): AddonRegistry = AddonRegistry(all.filterNot { it.url == url }, approvedCatalogs)

    fun withEnabled(url: AddonUrl, enabled: Boolean): AddonRegistry =
        AddonRegistry(all.map { if (it.url == url) it.copy(enabled = enabled) else it }, approvedCatalogs)

    /** Moves the addon at [url] to [toIndex] (clamped to the list). Unknown URLs change nothing. */
    fun moved(url: AddonUrl, toIndex: Int): AddonRegistry {
        val from = all.indexOfFirst { it.url == url }
        if (from < 0) return this
        val list = all.toMutableList()
        val item = list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), item)
        return AddonRegistry(list, approvedCatalogs)
    }

    /**
     * Catalogs to browse, from every enabled addon in addon order, then manifest order. Catalogs that
     * can't be opened without an extra (search-only and similar) are left out. [type] narrows to one type.
     */
    fun browseCatalogs(type: String? = null): List<CatalogRef> =
        catalogRefs(type).filter { it.catalog.requiredExtras.isEmpty() && (approvedCatalogs == null || it.key in approvedCatalogs) }

    /**
     * Catalogs for the Discovery page. Like [browseCatalogs], but a catalog whose required extras are all
     * `genre` with options is kept too (Discovery opens it on its first genre). A required `search`, `skip`
     * or any other extra still hides it. A limited registry (kids) gets exactly [browseCatalogs].
     */
    fun discoverCatalogs(type: String? = null): List<CatalogRef> {
        if (approvedCatalogs != null) return browseCatalogs(type)
        return catalogRefs(type).filter { it.catalog.discoverable }
    }

    /**
     * The types for Discovery's first drop-down: [DISCOVER_FIRST_TYPES] always, then the other types of the
     * discoverable catalogs in first-seen order, at most [MAX_EXTRA_TYPES] of them. A limited registry only
     * contributes the types of its approved catalogs.
     */
    fun discoverTypes(): List<String> {
        val extra = discoverCatalogs().map { it.catalog.type }.filter { it !in DISCOVER_FIRST_TYPES }.distinct().take(MAX_EXTRA_TYPES)
        return DISCOVER_FIRST_TYPES + extra
    }

    /** Catalogs that accept a `search` extra, in the same order. */
    fun searchCatalogs(type: String? = null): List<CatalogRef> =
        if (approvedCatalogs != null) emptyList() else catalogRefs(type).filter { ref -> ref.catalog.supports("search") && ref.catalog.requiredExtras.all { it == "search" } }

    /** Enabled addons that serve meta for this type and id, best first. */
    fun metaAddons(type: String, id: String): List<InstalledAddon> =
        enabled.filter { it.manifest.serves(AddonResources.META, type, id) }

    /** Enabled addons that serve streams for this type and video id, in addon order. */
    fun streamAddons(type: String, videoId: String): List<InstalledAddon> =
        enabled.filter { it.manifest.serves(AddonResources.STREAM, type, videoId) }

    // Catalogs are taken from manifest.catalogs alone: some addons list catalogs without naming the
    // "catalog" resource, and Stremio clients still show them.
    private fun catalogRefs(type: String?): List<CatalogRef> =
        enabled.flatMap { addon ->
            addon.manifest.catalogs.filter { type == null || it.type == type }.map { CatalogRef(addon, it) }
        }

    override fun toString(): String = "AddonRegistry(${all.joinToString()})"

    private companion object {
        val DISCOVER_FIRST_TYPES = listOf("movie", "series")
        const val MAX_EXTRA_TYPES = 20
    }
}

private val AddonCatalog.discoverable: Boolean
    get() = extra.filter { it.isRequired }.all { it.name == "genre" && it.options.any(String::isNotBlank) }

/** One catalog of one addon. */
data class CatalogRef(val addon: InstalledAddon, val catalog: AddonCatalog) {
    /**
     * Unique across addons and addon setups (see [CatalogKeys]): two addons may both have a "popular" movie
     * catalog, and a second configuration or a re-add of the same addon gets keys of its own.
     */
    val key: String get() = CatalogKeys.of(addon.setupId, catalog.type, catalog.id)
}

/**
 * Catalogue keys, which kids approvals hold: format version, the addon's [AddonSetupId], then the type and
 * catalogue id escaped so neither can contain the separator. No address, host or manifest id. Keys of any
 * other shape (such as the old manifest id and host form) are not valid and never match.
 */
object CatalogKeys {
    private const val VERSION = 2
    private const val SEP = '|'

    fun of(setupId: String, type: String, catalogId: String): String = buildString {
        append(VERSION).append(SEP).append(setupId).append(SEP)
        append(AddonUrl.encode(type)).append(SEP).append(AddonUrl.encode(catalogId))
    }

    /** True for a key [of] could have made. */
    fun isValid(key: String): Boolean {
        val parts = key.split(SEP)
        return parts.size == 4 && parts[0] == VERSION.toString() && AddonSetupId.isValid(parts[1])
    }

    /** The setup id of a valid key, else null. */
    fun setupOf(key: String): String? = if (isValid(key)) key.split(SEP)[1] else null
}
