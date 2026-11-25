package tv.own.owntv.gambit.trakt

import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every request goes to the fake host in FakeTrakt.kt; nothing reaches api.trakt.tv. */
class TraktListsTest {
    private val itemsPath = "/users/me/lists/42/items/movie,show,season,episode"

    /** [n] movie entries, as a JSON array. */
    private fun movies(n: Int): String = (1..n).joinToString(",", "[", "]") { i ->
        """{"rank":$i,"type":"movie","movie":{"title":"M$i","year":2000,"ids":{"trakt":$i,"imdb":"tt$i"}}}"""
    }

    private fun pagination(page: Int, pageCount: Int, limit: Int = 100) = mapOf(
        "X-Pagination-Page" to "$page",
        "X-Pagination-Limit" to "$limit",
        "X-Pagination-Page-Count" to "$pageCount",
        "X-Pagination-Item-Count" to "${pageCount * limit}",
    )

    @Test
    fun `my lists are read with the me id`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/users/me/lists", Canned(body = Fake.fixture("lists.json")))

        val lists = h.client.lists.mine().getOrNull()!!

        assertEquals(
            listOf(
                TraktList(42, "fake-favourites", "Fake Favourites", 250, Instant.parse("2026-10-01T10:00:00Z")),
                TraktList(43, null, null, 0, null),
            ),
            lists,
        )
        assertNull("the lists call is not paged", h.host.seen.single().query)
    }

    @Test
    fun `a list without a Trakt id is malformed`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/users/me/lists", Canned(body = """[{"name":"x","ids":{"slug":"x"}}]"""))

        assertEquals(TraktError.Malformed("missing 'ids.trakt'"), h.client.lists.mine().errorOrNull())
    }

    @Test
    fun `list items keep the order Trakt sent and skip people`() = runBlocking {
        val h = Harness()
        h.host.on("GET", itemsPath, Canned(body = Fake.fixture("list_items.json")))

        val page = h.client.lists.items(42, page = 1).getOrNull()!!

        assertEquals(listOf(1, 3, 4), page.entries.map { it.rank })
        assertEquals(1, page.skipped)
        assertEquals("Fake Movie", (page.entries[0].item as TraktMediaItem.Movie).movie.title)
        assertEquals("tt0000002", (page.entries[1].item as TraktMediaItem.Show).show.ids.imdb)
        assertEquals(6001L, (page.entries[2].item as TraktMediaItem.Episode).episode.show!!.ids.trakt)
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), page.entries[0].listedAt)
    }

    @Test
    fun `the comma in the type filter reaches the server intact, with page and limit and no sort`() = runBlocking {
        val h = Harness()
        h.host.on("GET", itemsPath, Canned(body = "[]"))

        h.client.lists.items(42, page = 3).getOrNull()!!

        val seen = h.host.seen.single()
        assertEquals(itemsPath, seen.path)
        assertFalse("comma must not be escaped", seen.path.contains("%2C", ignoreCase = true))
        assertEquals("page=3&limit=100", seen.query)
    }

    @Test
    fun `watchlist is paged with no sort segments`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/sync/watchlist/all", Canned(body = Fake.fixture("watchlist.json")))

        val page = h.client.lists.watchlist(page = 1).getOrNull()!!

        assertEquals(listOf(1, 2, 3, 4), page.entries.map { it.rank })
        assertEquals("page=1&limit=100", h.host.seen.single().query)
    }

    @Test
    fun `with pagination headers paging stops at the page count`() = runBlocking {
        val h = Harness()
        h.host.on(
            "GET", itemsPath,
            Canned(body = movies(100), headers = pagination(page = 1, pageCount = 2)),
            Canned(body = movies(100), headers = pagination(page = 2, pageCount = 2)),
        )

        val first = h.client.lists.items(42, page = 1).getOrNull()!!
        val second = h.client.lists.items(42, page = 2).getOrNull()!!

        assertTrue(first.hasMore)
        assertEquals(2, first.pageCount)
        assertEquals(200, first.itemCount)
        assertFalse("a full last page still ends when the header says so", second.hasMore)
    }

    @Test
    fun `an empty list with a page count of zero has no more`() = runBlocking {
        val h = Harness()
        h.host.on("GET", itemsPath, Canned(body = "[]", headers = pagination(page = 1, pageCount = 0)))

        assertFalse(h.client.lists.items(42, page = 1).getOrNull()!!.hasMore)
    }

    @Test
    fun `without headers a full page asks again and a short or empty page ends`() = runBlocking {
        val h = Harness()
        h.host.on("GET", itemsPath, Canned(body = movies(100)), Canned(body = movies(37)), Canned(body = "[]"))

        assertTrue(h.client.lists.items(42, page = 1).getOrNull()!!.hasMore)
        assertFalse(h.client.lists.items(42, page = 2).getOrNull()!!.hasMore)
        assertFalse(h.client.lists.items(42, page = 3).getOrNull()!!.hasMore)
    }

    @Test
    fun `skipped people still count towards a full page`() = runBlocking {
        val h = Harness()
        val person = """{"rank":3,"type":"person","person":{"name":"P","ids":{"trakt":9}}}"""
        h.host.on("GET", itemsPath, Canned(body = movies(2).dropLast(1) + ",$person]"))

        val page = h.client.lists.items(42, page = 1, limit = 3).getOrNull()!!

        assertEquals(2, page.entries.size)
        assertTrue(page.hasMore)
    }

    @Test
    fun `the end-of-list rule`() {
        val none = headersOf()
        assertTrue("full page", hasMorePages(1, 100, 100, none))
        assertFalse("short page", hasMorePages(1, 100, 99, none))
        assertFalse("empty page", hasMorePages(1, 100, 0, none))
        assertFalse("paging ignored: the whole list came back", hasMorePages(1, 100, 250, none))
        assertTrue("a smaller limit from Trakt wins", hasMorePages(1, 100, 50, headersOf("X-Pagination-Limit", "50")))
        assertTrue("header page below count", hasMorePages(1, 100, 0, headersOf("X-Pagination-Page", "1", "X-Pagination-Page-Count", "3")))
        assertFalse("header page at count", hasMorePages(1, 100, 100, headersOf("X-Pagination-Page", "3", "X-Pagination-Page-Count", "3")))
        assertFalse("asked page at count, no page header", hasMorePages(3, 100, 100, headersOf("X-Pagination-Page-Count", "3")))
        assertTrue("unreadable count falls back to page size", hasMorePages(1, 100, 100, headersOf("X-Pagination-Page-Count", "many")))
    }

    @Test
    fun `a known type with its object missing is malformed`() = runBlocking {
        val h = Harness()
        h.host.on("GET", itemsPath, Canned(body = """[{"rank":1,"type":"movie"}]"""))

        assertEquals(TraktError.Malformed("missing 'movie'"), h.client.lists.items(42, page = 1).errorOrNull())
    }

    @Test
    fun `a bad page or limit is refused without a request`() = runBlocking {
        val h = Harness()

        assertEquals(TraktError.Unprocessable, h.client.lists.items(42, page = 0).errorOrNull())
        assertEquals(TraktError.Unprocessable, h.client.lists.items(42, page = 1, limit = 0).errorOrNull())
        assertEquals(TraktError.Unprocessable, h.client.lists.watchlist(page = 1, limit = 101).errorOrNull())
        assertTrue(h.host.seen.isEmpty())
    }

    @Test
    fun `420 is an account limit`() = runBlocking {
        val h = Harness()
        h.host.on(
            "GET", itemsPath,
            Canned(status = 420, headers = mapOf("X-Account-Limit" to "100", "X-VIP-User" to "false", "X-Upgrade-URL" to "https://upgrade.example")),
        )

        assertEquals(TraktError.AccountLimit, h.client.lists.items(42, page = 1).errorOrNull())
    }

    @Test
    fun `429 carries Retry-After`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/sync/watchlist/all", Canned(status = 429, headers = mapOf("Retry-After" to "10")))

        assertEquals(TraktError.RateLimited(10), h.client.lists.watchlist(page = 1).errorOrNull())
    }

    @Test
    fun `401 refreshes once and retries, and no token is ever in a URL`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/users/me/lists", Canned(status = 401), Canned(body = Fake.fixture("lists.json")))
        h.host.on("GET", itemsPath, Canned(body = movies(1)))
        h.host.on("GET", "/sync/watchlist/all", Canned(body = "[]"))
        h.host.on("POST", "/oauth/token", Canned(body = Fake.fixture("token_ok.json")))

        assertEquals(2, h.client.lists.mine().getOrNull()!!.size)
        h.client.lists.items(42, page = 1).getOrNull()!!
        h.client.lists.watchlist(page = 1).getOrNull()!!

        assertEquals(1, h.host.seen("POST", "/oauth/token").size)
        val gets = h.host.seen.filter { it.method == "GET" }
        assertEquals(
            listOf("Bearer ${Fake.ACCESS}", "Bearer ${Fake.NEW_ACCESS}", "Bearer ${Fake.NEW_ACCESS}", "Bearer ${Fake.NEW_ACCESS}"),
            gets.map { it.headers["Authorization"] },
        )
        val secrets = listOf(Fake.ACCESS, Fake.NEW_ACCESS, Fake.REFRESH, Fake.NEW_REFRESH, Fake.CLIENT_ID, Fake.CLIENT_SECRET)
        for (seen in h.host.seen) {
            val url = seen.path + "?" + seen.query.orEmpty()
            secrets.forEach { assertFalse("secret in URL ${seen.path}", url.contains(it)) }
        }
    }

    @Test
    fun `a dead refresh token ends as sign-in expired`() = runBlocking {
        val h = Harness()
        h.host.on("GET", itemsPath, Canned(status = 401))
        h.host.on("POST", "/oauth/token", Canned(status = 401, body = Fake.fixture("token_invalid_grant.json")))

        assertEquals(TraktError.SignInExpired, h.client.lists.items(42, page = 1).errorOrNull())
        assertNull(h.store.tokens)
    }

    @Test
    fun `not signed in makes no request`() = runBlocking {
        val h = Harness(tokens = null)

        assertEquals(TraktError.NotSignedIn, h.client.lists.mine().errorOrNull())
        assertTrue(h.host.seen.isEmpty())
    }
}
