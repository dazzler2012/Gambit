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
class AddonRegistry(val all: List<InstalledAddon> = emptyList()) {

    val enabled: List<InstalledAddon> get() = all.filter { it.enabled }

    /** Adds [addon] at the end, or replaces the one with the same URL in place (keeping its position). */
    fun withAdded(addon: InstalledAddon): AddonRegistry {
        val index = all.indexOfFirst { it.url == addon.url }
        return AddonRegistry(if (index < 0) all + addon else all.toMutableList().also { it[index] = addon })
    }

    fun withRemoved(url: AddonUrl): AddonRegistry = AddonRegistry(all.filterNot { it.url == url })

    fun withEnabled(url: AddonUrl, enabled: Boolean): AddonRegistry =
        AddonRegistry(all.map { if (it.url == url) it.copy(enabled = enabled) else it })

    /** Moves the addon at [url] to [toIndex] (clamped to the list). Unknown URLs change nothing. */
    fun moved(url: AddonUrl, toIndex: Int): AddonRegistry {
        val from = all.indexOfFirst { it.url == url }
        if (from < 0) return this
        val list = all.toMutableList()
        val item = list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), item)
        return AddonRegistry(list)
    }

    /**
     * Catalogs to browse, from every enabled addon in addon order, then manifest order. Catalogs that
     * can't be opened without an extra (search-only and similar) are left out. [type] narrows to one type.
     */
    fun browseCatalogs(type: String? = null): List<CatalogRef> =
        catalogRefs(type).filter { it.catalog.requiredExtras.isEmpty() }

    /** Catalogs that accept a `search` extra, in the same order. */
    fun searchCatalogs(type: String? = null): List<CatalogRef> =
        catalogRefs(type).filter { ref -> ref.catalog.supports("search") && ref.catalog.requiredExtras.all { it == "search" } }

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
}

/** One catalog of one addon. */
data class CatalogRef(val addon: InstalledAddon, val catalog: AddonCatalog) {
    /** Unique across addons: two addons may both have a "popular" movie catalog. */
    val key: String get() = "${addon.manifest.id}|${addon.url.host}|${catalog.type}|${catalog.id}"
}
