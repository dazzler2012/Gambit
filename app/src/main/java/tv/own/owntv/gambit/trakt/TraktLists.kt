package tv.own.owntv.gambit.trakt

import okhttp3.Headers

/**
 * The signed-in user's personal lists and watchlist, read one page at a time.
 *
 * Paths and behaviour follow Trakt's API docs (Apiary): `GET /users/{id}/lists`, `GET /users/{id}/lists/
 * {list_id}/items/{type}` and `GET /sync/watchlist/{type}`. Both item calls are "Pagination Optional"
 * (everything at once unless page and limit are sent), so every call here sends both. No sort is sent:
 * then Trakt returns the list in its own default sort (the one the user chose on Trakt). Both item calls
 * also send `extended=images` for posters.
 */
class TraktLists internal constructor(
    private val http: TraktHttp,
    private val session: TraktSession,
) {
    /** Every personal list of the signed-in user, in the order Trakt sends them. Not paged by Trakt. */
    suspend fun mine(): TraktResult<List<TraktList>> = session.authorized { token ->
        http.send("GET", "users/me/lists", accessToken = token).flatMap { reply ->
            parsing { parseArray(reply.body).mapObjects { it.toTraktList() } }
        }
    }

    /** One page of the personal list [listId] (its Trakt id). Movies, shows, seasons and episodes only. */
    suspend fun items(listId: Long, page: Int, limit: Int = PAGE_LIMIT): TraktResult<TraktListPage> =
        pageOf("users/me/lists/$listId/items/$LIST_TYPES", page, limit)

    /** One page of the signed-in user's watchlist. */
    suspend fun watchlist(page: Int, limit: Int = PAGE_LIMIT): TraktResult<TraktListPage> =
        pageOf("sync/watchlist/all", page, limit)

    private suspend fun pageOf(path: String, page: Int, limit: Int): TraktResult<TraktListPage> {
        if (page < 1 || limit !in 1..PAGE_LIMIT) return TraktResult.Failure(TraktError.Unprocessable)
        val query = mapOf("page" to page.toString(), "limit" to limit.toString(), "extended" to EXTENDED_IMAGES)
        return session.authorized { token ->
            http.send("GET", path, accessToken = token, query = query).flatMap { reply ->
                parsing { readPage(reply.body, reply.headers, page, limit) }
            }
        }
    }

    companion object {
        /** Items asked for per page. Trakt documents no maximum; it may send fewer (see [hasMorePages]). */
        const val PAGE_LIMIT = 100

        /**
         * Asks Trakt to add an `images` object (posters) to each movie and show. Taken from Trakt's API docs
         * as relayed for this task; the docs host could not be read to confirm it (UNVERIFIED).
         */
        internal const val EXTENDED_IMAGES = "images"

        /** The type filter for list items: a comma-separated value in one path segment. Leaves out `person`. */
        internal const val LIST_TYPES = "movie,show,season,episode"
    }
}

private fun readPage(body: String, headers: Headers, page: Int, limit: Int): TraktListPage {
    val array = parseArray(body)
    val entries = array.mapObjects { it.toListEntryOrNull() }
    val kept = entries.filterNotNull()
    return TraktListPage(
        entries = kept,
        page = page,
        hasMore = hasMorePages(page, limit, array.length(), headers),
        pageCount = headers["X-Pagination-Page-Count"]?.toIntOrNull(),
        itemCount = headers["X-Pagination-Item-Count"]?.toIntOrNull(),
        skipped = entries.size - kept.size,
    )
}

/**
 * Whether a page after [page] exists. [received] is every entry in the reply, skipped ones included.
 *  - With X-Pagination-Page-Count: more while this page (Trakt's X-Pagination-Page, else the one asked
 *    for) is below it.
 *  - Without it: a full page (as many as asked for, or Trakt's X-Pagination-Limit) means maybe more; a
 *    short or empty page is the end. More than a full page means Trakt ignored paging and sent the whole
 *    list, so that is the end too (asking again would only repeat it).
 */
internal fun hasMorePages(page: Int, limit: Int, received: Int, headers: Headers): Boolean {
    val pageCount = headers["X-Pagination-Page-Count"]?.toIntOrNull()
    if (pageCount != null) {
        val current = headers["X-Pagination-Page"]?.toIntOrNull() ?: page
        return current < pageCount
    }
    val pageSize = headers["X-Pagination-Limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: limit
    return received == pageSize
}

private fun org.json.JSONObject.toTraktList(): TraktList {
    val ids = requireObject("ids").toIds()
    return TraktList(
        traktId = ids.trakt ?: throw TraktJsonException("missing 'ids.trakt'"),
        slug = ids.slug,
        name = stringOrNull("name"),
        itemCount = intOrNull("item_count"),
        updatedAt = instantOrNull("updated_at"),
    )
}

/** Null for an entry of a kind Gambit does not show (a person). */
private fun org.json.JSONObject.toListEntryOrNull(): TraktListEntry? {
    val item = toMediaItemOrNull() ?: return null
    return TraktListEntry(rank = intOrNull("rank"), listedAt = instantOrNull("listed_at"), item = item)
}
