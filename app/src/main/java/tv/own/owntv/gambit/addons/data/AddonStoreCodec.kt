package tv.own.owntv.gambit.addons.data

import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.gambit.addons.model.AddonCatalog
import tv.own.owntv.gambit.addons.model.AddonManifest
import tv.own.owntv.gambit.addons.model.AddonResource
import tv.own.owntv.gambit.addons.net.AddonJson
import tv.own.owntv.gambit.addons.net.AddonResult
import tv.own.owntv.gambit.addons.net.AddonSource
import tv.own.owntv.gambit.addons.net.AddonUrl

/**
 * The saved form of the addon list: each addon's address, on/off state and last manifest. The manifest
 * is written back in Stremio's own shape and read with [AddonJson.manifest], so loading the list needs
 * no network. Only the fields [AddonManifest] keeps are written; anything else the addon sent (such as
 * `config`) was already dropped by the parser.
 *
 * The text holds addon addresses, which carry the user's keys, so it is only ever stored encrypted
 * (see [AddonVault]). Pure: no Android, no I/O.
 */
internal object AddonStoreCodec {
    private const val VERSION = 1
    private val NO_SOURCE = AddonSource(null, "")

    fun encode(addons: List<InstalledAddon>): String = JSONObject()
        .put("v", VERSION)
        .put("addons", JSONArray().apply {
            addons.forEach { a ->
                put(JSONObject().put("url", a.url.manifestUrl).put("enabled", a.enabled).put("manifest", manifest(a.manifest)))
            }
        })
        .toString()

    /** The saved list, in order, or null when [text] can't be read at all. Unreadable entries are dropped. */
    fun decode(text: String): List<InstalledAddon>? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optInt("v") != VERSION) return null
        val items = root.optJSONArray("addons") ?: return null
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val url = AddonUrl.parse(o.optString("url")) ?: return@mapNotNull null
            val m = o.optJSONObject("manifest") ?: return@mapNotNull null
            val manifest = (AddonJson.manifest(m.toString(), NO_SOURCE) as? AddonResult.Ok)?.value ?: return@mapNotNull null
            InstalledAddon(url, manifest, enabled = o.optBoolean("enabled", true))
        }
    }

    private fun manifest(m: AddonManifest): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("name", m.name)
        put("version", m.version)
        m.description?.let { put("description", it) }
        m.logo?.let { put("logo", it) }
        put("types", JSONArray(m.types))
        m.idPrefixes?.let { put("idPrefixes", JSONArray(it)) }
        put("resources", JSONArray().apply { m.resources.forEach { put(resource(it)) } })
        put("catalogs", JSONArray().apply { m.catalogs.forEach { put(catalog(it)) } })
        put(
            "behaviorHints",
            JSONObject()
                .put("adult", m.behaviorHints.adult)
                .put("p2p", m.behaviorHints.p2p)
                .put("configurable", m.behaviorHints.configurable)
                .put("configurationRequired", m.behaviorHints.configurationRequired),
        )
    }

    private fun resource(r: AddonResource): JSONObject = JSONObject().apply {
        put("name", r.name)
        r.types?.let { put("types", JSONArray(it)) }
        r.idPrefixes?.let { put("idPrefixes", JSONArray(it)) }
    }

    private fun catalog(c: AddonCatalog): JSONObject = JSONObject().apply {
        put("type", c.type)
        put("id", c.id)
        c.name?.let { put("name", it) }
        put("extra", JSONArray().apply {
            c.extra.forEach { e ->
                put(
                    JSONObject()
                        .put("name", e.name)
                        .put("isRequired", e.isRequired)
                        .put("options", JSONArray(e.options))
                        .put("optionsLimit", e.optionsLimit),
                )
            }
        })
    }
}
