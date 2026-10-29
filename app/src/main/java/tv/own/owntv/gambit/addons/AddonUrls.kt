package tv.own.owntv.gambit.addons

import java.net.URI

/*
 * Stremio addon URLs. A configured addon's URL carries the user's own keys (debrid and the like) in its
 * path or query, so it is a secret: it is only ever turned into the manifest address to fetch, and shown
 * as its host. Adapted from NuvioTV / TuvoraTV (AddonManagerViewModel.normalizeAddonUrl), GPL-3.0.
 */
object AddonUrls {
    private const val MANIFEST = "/manifest.json"

    /**
     * The addon's base (manifest URL without "/manifest.json"), with its query kept, or null when [raw] is
     * not an addon address. stremio:// becomes https://; only http(s) is accepted.
     */
    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("stremio://", ignoreCase = true)) s = "https://" + s.substring("stremio://".length)
        if (!s.startsWith("https://", ignoreCase = true) && !s.startsWith("http://", ignoreCase = true)) return null
        val q = s.indexOf('?')
        var path = if (q >= 0) s.substring(0, q) else s
        val query = if (q >= 0) s.substring(q + 1) else ""
        path = path.trimEnd('/')
        if (path.endsWith(MANIFEST, ignoreCase = true)) path = path.dropLast(MANIFEST.length)
        path = path.trimEnd('/')
        if (hostOf(path) == null) return null
        return if (query.isEmpty()) path else "$path?$query"
    }

    /** The manifest's address for a normalized [base]. */
    fun manifestUrl(base: String): String {
        val q = base.indexOf('?')
        return if (q >= 0) base.substring(0, q) + MANIFEST + base.substring(q) else base + MANIFEST
    }

    /** Only the host ("torrentio.strem.fun"): what the screen may show of an addon's address. */
    fun hostOf(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.lowercase()?.takeIf { it.isNotBlank() }
}
