package tv.own.owntv.gambit.trakt

import java.time.Instant
import java.time.format.DateTimeParseException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** A response that parsed as JSON but not in the shape expected. [reason] names a field, never a value. */
internal class TraktJsonException(val reason: String) : RuntimeException(reason)

// org.json's opt* methods turn JSON null into "null" or 0, so these read null as null.

internal fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else get(key) as? String

internal fun JSONObject.longOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else (get(key) as? Number)?.toLong()

internal fun JSONObject.intOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else (get(key) as? Number)?.toInt()

internal fun JSONObject.doubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else (get(key) as? Number)?.toDouble()

internal fun JSONObject.objectOrNull(key: String): JSONObject? =
    if (!has(key) || isNull(key)) null else get(key) as? JSONObject

internal fun JSONObject.instantOrNull(key: String): Instant? {
    val raw = stringOrNull(key) ?: return null
    return try {
        Instant.parse(raw)
    } catch (e: DateTimeParseException) {
        null
    }
}

internal fun JSONObject.requireString(key: String): String = stringOrNull(key) ?: throw TraktJsonException("missing '$key'")

internal fun JSONObject.requireLong(key: String): Long = longOrNull(key) ?: throw TraktJsonException("missing '$key'")

internal fun JSONObject.requireInt(key: String): Int = intOrNull(key) ?: throw TraktJsonException("missing '$key'")

internal fun JSONObject.requireObject(key: String): JSONObject = objectOrNull(key) ?: throw TraktJsonException("missing '$key'")

internal fun JSONObject.requireInstant(key: String): Instant {
    if (stringOrNull(key) == null) throw TraktJsonException("missing '$key'")
    return instantOrNull(key) ?: throw TraktJsonException("bad date in '$key'")
}

internal fun parseObject(body: String): JSONObject =
    JSONObject(body.ifBlank { throw TraktJsonException("empty body") })

internal fun parseArray(body: String): JSONArray =
    JSONArray(body.ifBlank { throw TraktJsonException("empty body") })

internal inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { i ->
        val item = opt(i) as? JSONObject ?: throw TraktJsonException("array item $i is not an object")
        transform(item)
    }

// ---- Models <-> JSON ----

internal fun JSONObject.toIds(): TraktIds = TraktIds(
    trakt = longOrNull("trakt"),
    slug = stringOrNull("slug"),
    imdb = stringOrNull("imdb"),
    tmdb = longOrNull("tmdb"),
    tvdb = longOrNull("tvdb"),
)

internal fun TraktIds.toJson(): JSONObject = JSONObject().apply {
    trakt?.let { put("trakt", it) }
    slug?.let { put("slug", it) }
    imdb?.let { put("imdb", it) }
    tmdb?.let { put("tmdb", it) }
    tvdb?.let { put("tvdb", it) }
}

internal fun JSONObject.toMovie(): TraktMovie = TraktMovie(
    title = stringOrNull("title"),
    year = intOrNull("year"),
    ids = requireObject("ids").toIds(),
    poster = objectOrNull("images")?.posterUrl(),
)

internal fun JSONObject.toShow(): TraktShow = TraktShow(
    title = stringOrNull("title"),
    year = intOrNull("year"),
    ids = requireObject("ids").toIds(),
    poster = objectOrNull("images")?.posterUrl(),
)

/**
 * The first poster of an `images` object (`extended=images`): a list of addresses that Trakt sends
 * without a scheme, so `https://` is put in front. Anything that is not then a plain https address with
 * a host is ignored, and so is a missing or empty list: the title simply has no Trakt poster.
 */
internal fun JSONObject.posterUrl(): String? {
    val raw = when (val value = opt("poster")) {
        is JSONArray -> value.optString(0, "")
        is String -> value
        else -> ""
    }.trim()
    if (raw.isEmpty() || raw.any { it.isWhitespace() }) return null
    val bare = raw.removePrefix("https://").removePrefix("http://").removePrefix("//")
    val url = (HTTPS + bare).toHttpUrlOrNull() ?: return null
    return url.takeIf { it.isHttps && it.host.isNotEmpty() }?.toString()
}

private const val HTTPS = "https://"

internal fun JSONObject.toEpisode(show: TraktShow?): TraktEpisode = TraktEpisode(
    season = requireInt("season"),
    number = requireInt("number"),
    title = stringOrNull("title"),
    ids = requireObject("ids").toIds(),
    show = show,
)

internal fun JSONObject.toSeason(show: TraktShow?): TraktSeason = TraktSeason(
    number = requireInt("number"),
    ids = requireObject("ids").toIds(),
    show = show,
)

/** Reads the media item of a history, playback or watchlist entry, chosen by its "type" field. */
internal fun JSONObject.toMediaItem(): TraktMediaItem {
    val type = requireString("type")
    return toMediaItemOrNull() ?: throw TraktJsonException("unknown type '$type'")
}

/**
 * Like [toMediaItem], but a "type" this client does not know (a list can hold a `person`) gives null
 * instead of failing. A known type with a missing or broken object still fails.
 */
internal fun JSONObject.toMediaItemOrNull(): TraktMediaItem? {
    val show = objectOrNull("show")?.toShow()
    return when (requireString("type")) {
        "movie" -> TraktMediaItem.Movie(requireObject("movie").toMovie())
        "show" -> TraktMediaItem.Show(show ?: throw TraktJsonException("missing 'show'"))
        "season" -> TraktMediaItem.Season(requireObject("season").toSeason(show))
        "episode" -> TraktMediaItem.Episode(requireObject("episode").toEpisode(show))
        else -> null
    }
}

/** created_at + expires_in, as Trakt documents for the token responses. */
internal fun JSONObject.toTokens(): TraktTokens {
    val created = requireLong("created_at")
    val expiresIn = requireLong("expires_in")
    return TraktTokens(
        accessToken = requireString("access_token"),
        refreshToken = requireString("refresh_token"),
        expiresAtEpochSeconds = created + expiresIn,
        scope = stringOrNull("scope") ?: "public",
    )
}

internal fun JSONObject.toCounts(): TraktCounts = TraktCounts(
    movies = intOrNull("movies") ?: 0,
    shows = intOrNull("shows") ?: 0,
    seasons = intOrNull("seasons") ?: 0,
    episodes = intOrNull("episodes") ?: 0,
)

/** Counts the not_found entries per kind; the entries only echo back the IDs that were sent. */
internal fun JSONObject.toNotFoundCounts(): TraktCounts {
    fun size(key: String) = (opt(key) as? JSONArray)?.length() ?: 0
    return TraktCounts(movies = size("movies"), shows = size("shows"), seasons = size("seasons"), episodes = size("episodes"))
}
