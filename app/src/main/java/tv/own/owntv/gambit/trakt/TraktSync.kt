package tv.own.owntv.gambit.trakt

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject

/** The signed-in user's watched history, playback progress and watchlist. */
class TraktSync internal constructor(
    private val http: TraktHttp,
    private val session: TraktSession,
) {
    enum class HistoryType(internal val path: String) { MOVIES("movies"), SHOWS("shows"), SEASONS("seasons"), EPISODES("episodes") }

    enum class PlaybackType(internal val path: String) { MOVIES("movies"), EPISODES("episodes") }

    enum class WatchlistType(internal val path: String) { ALL("all"), MOVIES("movies"), SHOWS("shows"), SEASONS("seasons"), EPISODES("episodes") }

    /**
     * Watched history, most recent first, one page at a time. [type] null means movies and episodes.
     * Without a type the path is /sync/history, as in Trakt's legacy docs (the current spec only shows
     * the typed form). Unverified live.
     */
    suspend fun history(type: HistoryType? = null, page: Int = 1, limit: Int = 50): TraktResult<TraktPage<TraktHistoryEntry>> {
        val path = if (type == null) "sync/history" else "sync/history/${type.path}"
        return get(path, mapOf("page" to page.toString(), "limit" to limit.toString())) { body, headers ->
            parseArray(body).mapObjects { it.toHistoryEntry() }.toPage(headers)
        }
    }

    /** Paused, in-progress items, most recently paused first. [type] null means both. */
    suspend fun playback(type: PlaybackType? = null): TraktResult<List<TraktPlaybackEntry>> {
        val path = if (type == null) "sync/playback" else "sync/playback/${type.path}"
        return get(path) { body, _ -> parseArray(body).mapObjects { it.toPlaybackEntry() } }
    }

    /** The whole watchlist (or one [type] of it), in the user's own rank order. */
    suspend fun watchlist(type: WatchlistType = WatchlistType.ALL): TraktResult<List<TraktWatchlistEntry>> =
        // sort_by and sort_how are required path segments in the current spec; "rank"/"asc" is the user's own order.
        get("sync/watchlist/${type.path}/rank/asc") { body, _ -> parseArray(body).mapObjects { it.toWatchlistEntry() } }

    suspend fun addToWatchlist(items: List<TraktWatchlistTarget>): TraktResult<TraktWatchlistChange> =
        post("sync/watchlist", items) { json ->
            TraktWatchlistChange(
                changed = json.requireObject("added").toCounts(),
                existing = json.objectOrNull("existing")?.toCounts() ?: TraktCounts(),
                notFound = json.objectOrNull("not_found")?.toNotFoundCounts() ?: TraktCounts(),
            )
        }

    suspend fun removeFromWatchlist(items: List<TraktWatchlistTarget>): TraktResult<TraktWatchlistChange> =
        post("sync/watchlist/remove", items) { json ->
            TraktWatchlistChange(
                changed = json.requireObject("deleted").toCounts(),
                existing = TraktCounts(),
                notFound = json.objectOrNull("not_found")?.toNotFoundCounts() ?: TraktCounts(),
            )
        }

    private suspend fun <T> get(
        path: String,
        query: Map<String, String> = emptyMap(),
        read: (body: String, headers: Headers) -> T,
    ): TraktResult<T> = session.authorized { token ->
        http.send("GET", path, accessToken = token, query = query).flatMap { reply -> parsing { read(reply.body, reply.headers) } }
    }

    private suspend fun <T> post(path: String, items: List<TraktWatchlistTarget>, read: (JSONObject) -> T): TraktResult<T> {
        if (items.isEmpty() || items.any { it.ids.isEmpty }) return TraktResult.Failure(TraktError.Unprocessable)
        val body = items.toSyncBody()
        return session.authorized { token ->
            http.send("POST", path, body, accessToken = token).flatMap { reply -> parsing { read(parseObject(reply.body)) } }
        }
    }
}

internal fun List<TraktWatchlistTarget>.toSyncBody(): JSONObject {
    val body = JSONObject()
    fun add(key: String, ids: TraktIds) {
        val list = body.optJSONArray(key) ?: JSONArray().also { body.put(key, it) }
        list.put(JSONObject().put("ids", ids.toJson()))
    }
    forEach {
        when (it) {
            is TraktWatchlistTarget.Movie -> add("movies", it.ids)
            is TraktWatchlistTarget.Show -> add("shows", it.ids)
            is TraktWatchlistTarget.Season -> add("seasons", it.ids)
            is TraktWatchlistTarget.Episode -> add("episodes", it.ids)
        }
    }
    return body
}

private fun <T> List<T>.toPage(headers: Headers) = TraktPage(
    items = this,
    page = headers["X-Pagination-Page"]?.toIntOrNull(),
    limit = headers["X-Pagination-Limit"]?.toIntOrNull(),
    pageCount = headers["X-Pagination-Page-Count"]?.toIntOrNull(),
    itemCount = headers["X-Pagination-Item-Count"]?.toIntOrNull(),
)

private fun JSONObject.toHistoryEntry() = TraktHistoryEntry(
    id = requireLong("id"),
    watchedAt = requireInstant("watched_at"),
    action = stringOrNull("action"),
    item = toMediaItem(),
)

private fun JSONObject.toPlaybackEntry() = TraktPlaybackEntry(
    id = requireLong("id"),
    progress = doubleOrNull("progress") ?: throw TraktJsonException("missing 'progress'"),
    pausedAt = requireInstant("paused_at"),
    item = toMediaItem(),
)

private fun JSONObject.toWatchlistEntry() = TraktWatchlistEntry(
    id = longOrNull("id"),
    rank = intOrNull("rank"),
    listedAt = instantOrNull("listed_at"),
    item = toMediaItem(),
)
