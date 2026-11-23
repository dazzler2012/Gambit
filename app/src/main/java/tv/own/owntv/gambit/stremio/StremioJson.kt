package tv.own.owntv.gambit.stremio

import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject
import tv.own.owntv.gambit.addons.net.AddonUrl

/**
 * Reads the replies of Stremio's link and account services. Both wrap the answer as `{"result": ...}`
 * or `{"error": {"code": n, "message": ...}}` (stremio-core `APIResult`). Error messages and bodies are
 * never kept.
 */
internal object StremioJson {
    /** `link.stremio.com` answers this while a code is not approved yet, and also once it expired. */
    const val LINK_WAITING_CODE = 101

    fun linkCode(body: String): StremioResult<StremioLinkCode> = parse(body) { result ->
        val code = result.str("code") ?: return@parse malformed("missing code")
        val link = result.str("link") ?: return@parse malformed("missing link")
        StremioResult.Ok(StremioLinkCode(code, link))
    }

    fun linkStatus(body: String): StremioResult<StremioLinkStatus> {
        val root = root(body) ?: return malformed("invalid JSON")
        val error = root.optJSONObject("error")
        if (error != null && error.optInt("code", 0) == LINK_WAITING_CODE) return StremioResult.Ok(StremioLinkStatus.Waiting)
        return parse(body) { result ->
            val key = result.str("authKey") ?: return@parse malformed("missing authKey")
            StremioResult.Ok(StremioLinkStatus.Linked(StremioAuthKey(key)))
        }
    }

    fun addonCollection(body: String): StremioResult<StremioAddonCollection> = parse(body) { result ->
        val list = result.optJSONArray("addons") ?: return@parse malformed("missing addons")
        val addons = mutableListOf<StremioAccountAddon>()
        var unreadable = 0
        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i)
            val transport = entry?.str("transportUrl")
            val url = transport?.let(AddonUrl::parse)
            if (entry == null || transport == null || url == null) {
                unreadable++
                continue
            }
            val flags = entry.optJSONObject("flags")
            addons += StremioAccountAddon(
                name = entry.optJSONObject("manifest")?.str("name"),
                url = url,
                official = flags?.optBoolean("official", false) ?: false,
                protected = flags?.optBoolean("protected", false) ?: false,
                legacy = !isManifestAddress(transport),
            )
        }
        StremioResult.Ok(StremioAddonCollection(addons, unreadable))
    }

    /** True when [transportUrl] names a `manifest.json` (Stremio's current addon protocol). */
    private fun isManifestAddress(transportUrl: String): Boolean =
        transportUrl.substringBefore('#').substringBefore('?').trimEnd('/').endsWith("/manifest.json", ignoreCase = true)

    /** Unwraps `result`, turning `error` into [StremioError.Api] and any parse problem into [StremioError.Malformed]. */
    private inline fun <T> parse(body: String, block: (JSONObject) -> StremioResult<T>): StremioResult<T> {
        val root = root(body) ?: return malformed("invalid JSON")
        root.optJSONObject("error")?.let { return StremioResult.Err(StremioError.Api(it.optInt("code", 0))) }
        val result = root.optJSONObject("result") ?: return malformed("missing result")
        return try {
            block(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            malformed("unexpected shape")
        }
    }

    private fun root(body: String): JSONObject? = try {
        JSONObject(body)
    } catch (e: JSONException) {
        null
    }

    private fun malformed(reason: String): StremioResult<Nothing> = StremioResult.Err(StremioError.Malformed(reason))

    private fun JSONObject.str(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }
}
