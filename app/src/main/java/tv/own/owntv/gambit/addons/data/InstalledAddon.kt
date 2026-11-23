package tv.own.owntv.gambit.addons.data

import tv.own.owntv.gambit.addons.model.AddonManifest
import tv.own.owntv.gambit.addons.net.AddonSource
import tv.own.owntv.gambit.addons.net.AddonUrl

/**
 * An addon the user has added: where it lives, its last fetched manifest and whether it's switched on.
 * Identity is the [url]; two configurations of the same addon (same manifest id) are two addons.
 */
data class InstalledAddon(
    val url: AddonUrl,
    val manifest: AddonManifest,
    val enabled: Boolean = true,
    /**
     * This install's [AddonSetupId]. Kept when the addon is turned on or off or moved; new whenever it is
     * installed or its manifest is fetched again. Not secret, but left out of [toString] like the rest.
     */
    val setupId: String,
) {
    /** What errors carry to name this addon: its name and host, never the URL. The screen words it. */
    val source: AddonSource get() = AddonSource(manifest.name, url.host)

    override fun toString(): String = "InstalledAddon(${manifest.name}, ${url.host}, enabled=$enabled)"
}
