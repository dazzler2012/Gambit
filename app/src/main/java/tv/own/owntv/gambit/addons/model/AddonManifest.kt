package tv.own.owntv.gambit.addons.model

/**
 * A Stremio addon's manifest.json, reduced to what Gambit reads. Unknown fields are ignored.
 *
 * [types] and [idPrefixes] are the addon-wide defaults; a [AddonResource] may narrow either.
 */
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String? = null,
    val logo: String? = null,
    val resources: List<AddonResource> = emptyList(),
    val types: List<String> = emptyList(),
    val catalogs: List<AddonCatalog> = emptyList(),
    /** Null means the addon accepts any id. */
    val idPrefixes: List<String>? = null,
    val behaviorHints: ManifestBehaviorHints = ManifestBehaviorHints(),
) {
    /**
     * True when this addon should be asked for [resource] of [type] for content [id]: it must list the
     * resource, the type and an id prefix that [id] starts with. Types and prefixes set on the resource
     * win over the addon-wide ones; no prefixes at either level accepts every id.
     */
    fun serves(resource: String, type: String, id: String): Boolean {
        val entry = resources.firstOrNull { it.name == resource } ?: return false
        val supportedTypes = entry.types ?: types
        if (type !in supportedTypes) return false
        val prefixes = entry.idPrefixes ?: idPrefixes ?: return true
        return prefixes.any { id.startsWith(it) }
    }
}

/**
 * One entry of manifest `resources`. A plain string in the manifest becomes a resource with null
 * [types] and [idPrefixes], which means "use the addon-wide values".
 */
data class AddonResource(
    val name: String,
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null,
)

/** One entry of manifest `catalogs`. */
data class AddonCatalog(
    val type: String,
    val id: String,
    val name: String? = null,
    val extra: List<CatalogExtra> = emptyList(),
) {
    fun supports(extraName: String): Boolean = extra.any { it.name == extraName }

    /** True when the catalog can't be fetched without one of these extras (e.g. a search-only catalog). */
    val requiredExtras: List<String> get() = extra.filter { it.isRequired }.map { it.name }
}

/** A catalog extra property such as `search`, `genre` or `skip`. */
data class CatalogExtra(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String> = emptyList(),
    val optionsLimit: Int = 1,
)

data class ManifestBehaviorHints(
    val adult: Boolean = false,
    val p2p: Boolean = false,
    val configurable: Boolean = false,
    val configurationRequired: Boolean = false,
)

/** The well-known resource names. */
object AddonResources {
    const val CATALOG = "catalog"
    const val META = "meta"
    const val STREAM = "stream"
    const val SUBTITLES = "subtitles"
}
