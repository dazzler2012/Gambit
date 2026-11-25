package tv.own.owntv.gambit.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.trakt.Canned
import tv.own.owntv.gambit.trakt.Fake
import tv.own.owntv.gambit.trakt.FakeTraktHost
import tv.own.owntv.gambit.trakt.Harness
import tv.own.owntv.gambit.trakt.MemoryTokenStore
import tv.own.owntv.gambit.trakt.TraktClient
import tv.own.owntv.gambit.trakt.TraktError

/** Every request goes to the fake Trakt host (FakeTrakt.kt); nothing reaches api.trakt.tv. */
class TraktListBoardTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_700_000_000_000L
    private val host = FakeTraktHost()

    /** Requests whose path holds [slowPath] are held for [slowMillis] before the fake host answers. */
    private var slowPath: String? = null
    private val slowMillis = 400L
    private val slow = Interceptor { chain ->
        if (slowPath != null && chain.request().url.encodedPath.contains(slowPath!!)) Thread.sleep(slowMillis)
        chain.proceed(chain.request())
    }

    private val client = TraktClient(
        config = Fake.config,
        tokenStore = MemoryTokenStore(Harness.validTokens()),
        httpClient = OkHttpClient.Builder().addInterceptor(slow).addInterceptor(host).build(),
        nowMillis = { now },
        sleep = {},
    )
    private val board = TraktListBoard(client.lists, scope) { now }

    @After
    fun tearDown() = scope.cancel()

    private val listPath = "/users/me/lists/42/items/movie,show,season,episode"
    private val watchPath = "/sync/watchlist/all"
    private val list = TraktListSource.Personal(42)

    /** Movies with imdb ids tt<from>..tt<to>, as a JSON array. */
    private fun movies(from: Int, to: Int): String = (from..to).joinToString(",", "[", "]") { i ->
        """{"rank":$i,"type":"movie","movie":{"title":"M$i","year":2000,"ids":{"trakt":$i,"imdb":"tt$i"}}}"""
    }

    private fun pages(page: Int, count: Int) = mapOf("X-Pagination-Page" to "$page", "X-Pagination-Page-Count" to "$count")

    private suspend fun await(predicate: (TraktListState) -> Boolean): TraktListState =
        withTimeout(5_000) { board.state.first(predicate) }

    private suspend fun awaitComplete(): TraktListFeed.Loaded =
        await { (it.feed as? TraktListFeed.Loaded)?.complete == true }.feed as TraktListFeed.Loaded

    private fun ids(feed: TraktListFeed) = (feed as? TraktListFeed.Loaded)?.items?.map { it.id }.orEmpty()

    private fun queries(path: String) = host.seen.filter { it.path == path }.map { it.query }

    @Test
    fun `a list is read to its end one page at a time, in Trakt's order`() = runBlocking {
        host.on(
            "GET", listPath,
            Canned(body = movies(1, 3), headers = pages(1, 3)),
            Canned(body = movies(4, 5), headers = pages(2, 3)),
            Canned(body = movies(6, 6), headers = pages(3, 3)),
        )
        board.open(list)
        assertEquals(TraktListFeed.Loading, board.state.value.feed)
        val done = awaitComplete()
        assertEquals((1..6).map { "tt$it" }, done.items.map { it.id })
        assertEquals(listOf("page=1&limit=100", "page=2&limit=100", "page=3&limit=100"), queries(listPath))
        assertEquals(list, board.state.value.source)
    }

    @Test
    fun `titles show while later pages are still on their way`() = runBlocking {
        host.on("GET", listPath, Canned(body = movies(1, 2), headers = pages(1, 2)), Canned(body = movies(3, 3), headers = pages(2, 2)))
        slowPath = "/lists/42/"
        board.open(list)
        val partial = await { (it.feed as? TraktListFeed.Loaded)?.complete == false }.feed as TraktListFeed.Loaded
        assertEquals(listOf("tt1", "tt2"), partial.items.map { it.id })
        assertNull(partial.error)
        assertEquals(listOf("tt1", "tt2", "tt3"), awaitComplete().items.map { it.id })
    }

    @Test
    fun `the watchlist uses its own call`() = runBlocking {
        host.on("GET", watchPath, Canned(body = movies(7, 8)))
        board.open(TraktListSource.Watchlist)
        assertEquals(listOf("tt7", "tt8"), awaitComplete().items.map { it.id })
        assertEquals(listOf("page=1&limit=100"), queries(watchPath))
    }

    @Test
    fun `an empty list is empty`() = runBlocking {
        host.on("GET", listPath, Canned(body = "[]", headers = pages(1, 0)))
        board.open(list)
        await { it.feed == TraktListFeed.Empty }
        Unit
    }

    @Test
    fun `a failed first page can be retried`() = runBlocking {
        host.on("GET", listPath, Canned(status = 500), Canned(body = movies(1, 1)))
        board.open(list)
        val failed = await { it.feed is TraktListFeed.Failed }.feed as TraktListFeed.Failed
        assertEquals(TraktError.Server(500), failed.error)
        board.retry()
        assertEquals(listOf("tt1"), awaitComplete().items.map { it.id })
        assertEquals(listOf("page=1&limit=100", "page=1&limit=100"), queries(listPath))
    }

    @Test
    fun `a failed later page keeps the titles and a retry goes on from that page`() = runBlocking {
        host.on(
            "GET", listPath,
            Canned(body = movies(1, 2), headers = pages(1, 2)),
            Canned(status = 429, headers = mapOf("Retry-After" to "5")),
            Canned(body = movies(3, 3), headers = pages(2, 2)),
        )
        board.open(list)
        val stopped = await { (it.feed as? TraktListFeed.Loaded)?.error != null }.feed as TraktListFeed.Loaded
        assertEquals(listOf("tt1", "tt2"), stopped.items.map { it.id })
        assertEquals(TraktError.RateLimited(5), stopped.error)
        board.retry()
        assertEquals(listOf("tt1", "tt2", "tt3"), awaitComplete().items.map { it.id })
        assertEquals(listOf("page=1&limit=100", "page=2&limit=100", "page=2&limit=100"), queries(listPath))
    }

    @Test
    fun `retry does nothing when nothing failed`() = runBlocking {
        host.on("GET", listPath, Canned(body = movies(1, 1)))
        board.open(list)
        awaitComplete()
        board.retry()
        delay(100)
        assertEquals(1, host.seen.size)
    }

    @Test
    fun `a finished list is reused for ten minutes, then read again`() = runBlocking {
        host.on("GET", listPath, Canned(body = movies(1, 1)), Canned(body = movies(1, 2)))
        host.on("GET", watchPath, Canned(body = movies(9, 9)))
        board.open(list)
        awaitComplete()
        board.open(TraktListSource.Watchlist)
        await { ids(it.feed) == listOf("tt9") }

        now += TraktListBoard.REUSE_MILLIS - 1
        board.open(list)
        assertEquals(listOf("tt1"), ids(board.state.value.feed)) // straight from memory
        assertEquals(1, queries(listPath).size)

        now += 1
        board.open(TraktListSource.Watchlist)
        await { ids(it.feed) == listOf("tt9") }
        board.open(list)
        await { ids(it.feed) == listOf("tt1", "tt2") }
        assertEquals(2, queries(listPath).size)
    }

    @Test
    fun `forget drops the kept lists and the one on screen`() = runBlocking {
        host.on("GET", listPath, Canned(body = movies(1, 1)))
        board.open(list)
        awaitComplete()
        board.forget()
        assertEquals(TraktListState(), board.state.value)
        board.open(list)
        awaitComplete()
        assertEquals(2, queries(listPath).size)
    }

    @Test
    fun `a reply that arrives after forget is dropped`() = runBlocking {
        host.on("GET", listPath, Canned(body = movies(1, 1)))
        slowPath = "/lists/42/"
        board.open(list)
        delay(50)
        board.forget()
        delay(slowMillis + 300)
        assertEquals(TraktListState(), board.state.value)
    }

    @Test
    fun `a late reply for the old list is dropped`() = runBlocking {
        host.on("GET", listPath, Canned(body = movies(1, 1)))
        host.on("GET", watchPath, Canned(body = movies(9, 9)))
        slowPath = "/lists/42/"
        board.open(list)
        delay(50)
        board.open(TraktListSource.Watchlist)
        await { ids(it.feed) == listOf("tt9") }
        delay(slowMillis + 300)
        assertEquals(TraktListSource.Watchlist, board.state.value.source)
        assertEquals(listOf("tt9"), ids(board.state.value.feed))
        // The dropped read was not kept: opening the list again asks Trakt again.
        val before = queries(listPath).size
        board.open(list)
        assertEquals(listOf("tt1"), awaitComplete().items.map { it.id })
        assertEquals(before + 1, queries(listPath).size)
    }

    @Test
    fun `opening the list being read does not start a second read`() = runBlocking {
        host.on("GET", listPath, Canned(body = movies(1, 1)))
        slowPath = "/lists/42/"
        board.open(list)
        delay(50)
        board.open(list)
        awaitComplete()
        assertEquals(1, queries(listPath).size)
    }

    @Test
    fun `a server that never ends the list stops at the page guard`() = runBlocking {
        host.on("GET", listPath, Canned(body = movies(1, 100))) // a full page each time, no headers
        board.open(list)
        val done = awaitComplete()
        assertEquals(100, done.items.size)
        assertEquals(TraktListBoard.MAX_PAGES, queries(listPath).size)
        assertTrue(queries(listPath).last() == "page=${TraktListBoard.MAX_PAGES}&limit=100")
    }
}
