package tv.own.owntv.gambit.stremio

import tv.own.owntv.gambit.addons.data.InstalledAddon
import tv.own.owntv.gambit.addons.net.AddonUrl

/** How one of the account's addons is offered in the import preview. */
enum class ImportKind {
    /** Not on this TV yet. Ticked to start with. */
    NEW,

    /** One of Stremio's own addons (Cinemeta, YouTube, WatchHub and others). Offered, but not ticked. */
    STREMIO_OWN,

    /** Already on this TV at the same address. Shown greyed out; it can't be ticked and is never touched. */
    ALREADY_ADDED,
}

/**
 * One row of the import preview. The screen shows [name] only; [url] is an addon address (it may hold
 * debrid keys) kept for the import itself, so [toString] shows the host only.
 */
data class ImportCandidate(val name: String?, val url: AddonUrl, val kind: ImportKind) {
    val selectable: Boolean get() = kind != ImportKind.ALREADY_ADDED
    val tickedByDefault: Boolean get() = kind == ImportKind.NEW

    override fun toString(): String = "ImportCandidate($name, ${url.host}, $kind)"
}

/**
 * What the account offers, in Stremio's order. [unsupported] counts addons left out because Gambit
 * can't use them (Local Files, old-protocol addons); [unreadable] counts entries with no usable address.
 */
data class ImportPreview(val candidates: List<ImportCandidate>, val unsupported: Int, val unreadable: Int)

/** Turns the account's addon list into the preview. Pure: no I/O. */
object StremioImportRules {
    fun preview(collection: StremioAddonCollection, installed: List<InstalledAddon>): ImportPreview {
        // Compared as sent (AddonUrl.canonical), so `|` and `%7C` are the same addon.
        val have = installed.map { it.url.canonical }.toSet()
        val seen = mutableSetOf<String>()
        val rows = mutableListOf<ImportCandidate>()
        var unsupported = 0
        for (addon in collection.addons) {
            if (!seen.add(addon.url.canonical)) continue
            if (isUnsupported(addon)) {
                unsupported++
                continue
            }
            val kind = when {
                addon.url.canonical in have -> ImportKind.ALREADY_ADDED
                addon.official -> ImportKind.STREMIO_OWN
                else -> ImportKind.NEW
            }
            rows += ImportCandidate(addon.name, addon.url, kind)
        }
        return ImportPreview(rows, unsupported, collection.unreadable)
    }

    /**
     * Addons Gambit can't use: ones on Stremio's old protocol, and ones served from the device itself
     * (Local Files lives on Stremio's own local server, which the TV doesn't run).
     */
    internal fun isUnsupported(addon: StremioAccountAddon): Boolean =
        addon.legacy || isLoopback(addon.url.host)

    private fun isLoopback(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        return h == "localhost" || h == "::1" || h.startsWith("127.")
    }
}
