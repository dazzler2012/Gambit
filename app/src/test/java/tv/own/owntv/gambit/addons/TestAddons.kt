package tv.own.owntv.gambit.addons

import tv.own.owntv.gambit.addons.data.InstalledAddon
import tv.own.owntv.gambit.addons.model.AddonManifest
import tv.own.owntv.gambit.addons.net.AddonJson
import tv.own.owntv.gambit.addons.net.AddonResult
import tv.own.owntv.gambit.addons.net.AddonUrl

/** Installed addons built from fixtures, at fake addresses. */
object TestAddons {
    const val META_BASE = "https://meta.example/KEY-META"
    const val LISTS_BASE = "https://lists.example/KEY-LISTS"
    const val STREAMS_A_BASE = "https://streams-a.example/KEY-A"
    const val STREAMS_B_BASE = "https://streams-b.example/KEY-B"

    fun manifest(fixture: String): AddonManifest =
        (AddonJson.manifest(Fixtures.read(fixture), "test") as AddonResult.Ok).value

    fun installed(base: String, fixture: String, enabled: Boolean = true) =
        InstalledAddon(AddonUrl.parse("$base/manifest.json")!!, manifest(fixture), enabled)

    val meta get() = installed(META_BASE, "manifest_meta.json")
    val lists get() = installed(LISTS_BASE, "manifest_lists.json")
    val streamsA get() = installed(STREAMS_A_BASE, "manifest_streams.json")
    val streamsB get() = installed(STREAMS_B_BASE, "manifest_streams.json")
}
