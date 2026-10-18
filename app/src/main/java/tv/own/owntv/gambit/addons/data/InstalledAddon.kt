package tv.own.owntv.gambit.addons.data

import tv.own.owntv.gambit.addons.model.AddonManifest
import tv.own.owntv.gambit.addons.net.AddonUrl

/**
 * An addon the user has added: where it lives, its last fetched manifest and whether it's switched on.
 * Identity is the [url]; two configurations of the same addon (same manifest id) are two addons.
 */
data class InstalledAddon(
    val url: AddonUrl,
    val manifest: AddonManifest,
    val enabled: Boolean = true,
) {
    /** Safe to show or put on an error: the addon's name and host, never the URL. */
    val label: String get() = "${manifest.name} (${url.host})"

    override fun toString(): String = "InstalledAddon($label, enabled=$enabled)"
}
