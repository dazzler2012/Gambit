package tv.own.owntv.gambit.addons

import org.json.JSONArray
import org.json.JSONObject

/** The parts of a Stremio addon manifest Stage 1 shows: who it is, what it serves. */
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val types: List<String>,
    val resources: List<String>,
    val catalogCount: Int,
) {
    companion object {
        /** Null when [json] is not a Stremio manifest (it needs at least an id and a name). */
        fun parse(json: String): AddonManifest? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val id = o.optString("id").trim()
            val name = o.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) return null
            // resources[] holds strings, or objects with a "name" (and their own types / idPrefixes).
            val resources = o.optJSONArray("resources").strings { item -> (item as? JSONObject)?.optString("name") ?: item as? String }
            return AddonManifest(
                id = id,
                name = name,
                version = o.optString("version").trim(),
                types = o.optJSONArray("types").strings { it as? String },
                resources = resources,
                catalogCount = o.optJSONArray("catalogs")?.length() ?: 0,
            )
        }

        private fun JSONArray?.strings(read: (Any?) -> String?): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { read(opt(it))?.trim()?.takeIf { s -> s.isNotEmpty() } }.distinct()
        }
    }
}
