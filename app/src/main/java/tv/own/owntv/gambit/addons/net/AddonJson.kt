package tv.own.owntv.gambit.addons.net

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import tv.own.owntv.gambit.addons.model.AddonCatalog
import tv.own.owntv.gambit.addons.model.AddonManifest
import tv.own.owntv.gambit.addons.model.AddonResource
import tv.own.owntv.gambit.addons.model.AddonStream
import tv.own.owntv.gambit.addons.model.AddonSubtitle
import tv.own.owntv.gambit.addons.model.AddonVideo
import tv.own.owntv.gambit.addons.model.CatalogExtra
import tv.own.owntv.gambit.addons.model.ManifestBehaviorHints
import tv.own.owntv.gambit.addons.model.MetaDetail
import tv.own.owntv.gambit.addons.model.MetaPreview
import tv.own.owntv.gambit.addons.model.StreamBehaviorHints

/**
 * Parsers for addon responses. They accept what real addons send rather than what the protocol
 * promises: optional fields may be missing, null, a number instead of a string, or one string instead
 * of a list. Only a missing required field fails the parse. Items of a list that can't be read
 * are dropped, not fatal.
 *
 * [addon] is the redacted label put on errors. No error keeps the parser's message, which can quote the body.
 */
object AddonJson {

    fun manifest(body: String, addon: String): AddonResult<AddonManifest> = parse(body, addon) { root ->
        val id = root.str("id") ?: return@parse AddonResult.Err(AddonError.MissingField(addon, "id"))
        val name = root.str("name") ?: return@parse AddonResult.Err(AddonError.MissingField(addon, "name"))
        val types = root.strings("types")
        val hints = root.optJSONObject("behaviorHints")
        AddonResult.Ok(
            AddonManifest(
                id = id,
                name = name,
                version = root.str("version") ?: "",
                description = root.str("description"),
                logo = root.str("logo"),
                resources = root.optJSONArray("resources").objectsOrStrings().mapNotNull(::resource),
                types = types,
                catalogs = root.optJSONArray("catalogs").objects().mapNotNull(::catalog),
                idPrefixes = root.stringsOrNull("idPrefixes"),
                behaviorHints = ManifestBehaviorHints(
                    adult = hints?.optBoolean("adult") ?: false,
                    p2p = hints?.optBoolean("p2p") ?: false,
                    configurable = hints?.optBoolean("configurable") ?: false,
                    configurationRequired = hints?.optBoolean("configurationRequired") ?: false,
                ),
            )
        )
    }

    /** `{"metas": [...]}`. Items without an id are dropped; a missing item type falls back to [type]. */
    fun catalog(body: String, addon: String, type: String): AddonResult<List<MetaPreview>> = parse(body, addon) { root ->
        val metas = root.optJSONArray("metas") ?: return@parse AddonResult.Err(AddonError.MissingField(addon, "metas"))
        AddonResult.Ok(metas.objects().mapNotNull { metaPreview(it, type) })
    }

    /** `{"meta": {...}}`. A null or absent meta is [AddonError.NotFound]. */
    fun meta(body: String, addon: String, type: String): AddonResult<MetaDetail> = parse(body, addon) { root ->
        val meta = root.optJSONObject("meta") ?: return@parse AddonResult.Err(AddonError.NotFound(addon))
        val id = meta.str("id") ?: return@parse AddonResult.Err(AddonError.MissingField(addon, "meta.id"))
        AddonResult.Ok(
            MetaDetail(
                id = id,
                type = meta.str("type") ?: type,
                name = meta.str("name"),
                poster = meta.str("poster"),
                background = meta.str("background"),
                logo = meta.str("logo"),
                description = meta.str("description"),
                releaseInfo = meta.str("releaseInfo") ?: meta.str("year"),
                imdbRating = meta.str("imdbRating"),
                runtime = meta.str("runtime"),
                genres = meta.strings("genres"),
                cast = meta.strings("cast"),
                director = meta.strings("director"),
                videos = meta.optJSONArray("videos").objects().mapNotNull(::video),
            )
        )
    }

    /** `{"streams": [...]}`. All streams are returned; [AddonStream.support] tells which can play. */
    fun streams(body: String, addon: String): AddonResult<List<AddonStream>> = parse(body, addon) { root ->
        val streams = root.optJSONArray("streams") ?: return@parse AddonResult.Err(AddonError.MissingField(addon, "streams"))
        AddonResult.Ok(streams.objects().map(::stream))
    }

    private inline fun <T> parse(body: String, addon: String, block: (JSONObject) -> AddonResult<T>): AddonResult<T> =
        try {
            val root = JSONObject(body)
            block(root)
        } catch (_: JSONException) {
            AddonResult.Err(AddonError.MalformedJson(addon))
        }

    private fun resource(value: Any): AddonResource? = when (value) {
        is String -> value.takeIf { it.isNotBlank() }?.let { AddonResource(it) }
        is JSONObject -> value.str("name")?.let {
            AddonResource(name = it, types = value.stringsOrNull("types"), idPrefixes = value.stringsOrNull("idPrefixes"))
        }
        else -> null
    }

    private fun catalog(obj: JSONObject): AddonCatalog? {
        val type = obj.str("type") ?: return null
        val id = obj.str("id") ?: return null
        val extra = obj.optJSONArray("extra")?.let { array ->
            array.objectsOrStrings().mapNotNull { item ->
                when (item) {
                    is String -> CatalogExtra(item)
                    is JSONObject -> item.str("name")?.let {
                        CatalogExtra(
                            name = it,
                            isRequired = item.optBoolean("isRequired"),
                            options = item.strings("options"),
                            optionsLimit = item.optInt("optionsLimit", 1),
                        )
                    }
                    else -> null
                }
            }
        } ?: run {
            // Older manifests list extras as two name arrays instead of `extra` objects.
            val required = obj.strings("extraRequired").toSet()
            (obj.strings("extraSupported") + required).distinct().map { CatalogExtra(it, isRequired = it in required) }
        }
        return AddonCatalog(type = type, id = id, name = obj.str("name"), extra = extra)
    }

    private fun metaPreview(obj: JSONObject, type: String): MetaPreview? {
        val id = obj.str("id") ?: return null
        return MetaPreview(
            id = id,
            type = obj.str("type") ?: type,
            name = obj.str("name"),
            poster = obj.str("poster"),
            posterShape = obj.str("posterShape"),
            background = obj.str("background"),
            logo = obj.str("logo"),
            description = obj.str("description"),
            releaseInfo = obj.str("releaseInfo") ?: obj.str("year"),
            imdbRating = obj.str("imdbRating"),
            genres = obj.strings("genres"),
        )
    }

    private fun video(obj: JSONObject): AddonVideo? {
        val id = obj.str("id") ?: return null
        return AddonVideo(
            id = id,
            title = obj.str("title") ?: obj.str("name"),
            season = obj.int("season"),
            episode = obj.int("episode") ?: obj.int("number"),
            released = obj.str("released"),
            thumbnail = obj.str("thumbnail"),
            overview = obj.str("overview") ?: obj.str("description"),
        )
    }

    private fun stream(obj: JSONObject): AddonStream {
        val hints = obj.optJSONObject("behaviorHints")
        val requestHeaders = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")
        return AddonStream(
            name = obj.str("name"),
            title = obj.str("title"),
            description = obj.str("description"),
            url = obj.str("url"),
            infoHash = obj.str("infoHash"),
            fileIdx = obj.int("fileIdx"),
            ytId = obj.str("ytId"),
            externalUrl = obj.str("externalUrl"),
            behaviorHints = StreamBehaviorHints(
                notWebReady = hints?.optBoolean("notWebReady") ?: false,
                bingeGroup = hints?.str("bingeGroup"),
                filename = hints?.str("filename"),
                videoSize = hints?.long("videoSize"),
                requestHeaders = requestHeaders?.let { headers ->
                    headers.keys().asSequence().mapNotNull { key -> headers.str(key)?.let { key to it } }.toMap()
                } ?: emptyMap(),
            ),
            subtitles = obj.optJSONArray("subtitles").objects().mapNotNull { sub ->
                sub.str("url")?.let { AddonSubtitle(id = sub.str("id"), url = it, lang = sub.str("lang")) }
            },
        )
    }

    // Field readers: missing, null, blank and non-primitive all read as null.

    private fun JSONObject.str(key: String): String? = when (val v = opt(key)) {
        is String -> v.trim().takeIf { it.isNotEmpty() }
        is Number, is Boolean -> v.toString()
        else -> null
    }

    private fun JSONObject.int(key: String): Int? = when (val v = opt(key)) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    private fun JSONObject.long(key: String): Long? = when (val v = opt(key)) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    private fun JSONObject.strings(key: String): List<String> = stringsOrNull(key) ?: emptyList()

    /** A list of strings, or one string as a list of one. Null when the key is absent or null. */
    private fun JSONObject.stringsOrNull(key: String): List<String>? = when (val v = opt(key)) {
        is JSONArray -> (0 until v.length()).mapNotNull { i ->
            when (val item = v.opt(i)) {
                is String -> item.trim().takeIf { it.isNotEmpty() }
                is Number -> item.toString()
                else -> null
            }
        }
        is String -> listOfNotNull(v.trim().takeIf { it.isNotEmpty() })
        else -> null
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.objectsOrStrings(): List<Any> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { i -> opt(i)?.takeIf { it is String || it is JSONObject } }
}
