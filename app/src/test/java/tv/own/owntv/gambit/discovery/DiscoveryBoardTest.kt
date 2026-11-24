package tv.own.owntv.gambit.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.FakeAddonServer
import tv.own.owntv.gambit.addons.FakeAddonServer.Reply
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.data.InstalledAddon
import tv.own.owntv.gambit.addons.model.AddonCatalog
import tv.own.owntv.gambit.addons.model.AddonManifest
import tv.own.owntv.gambit.addons.model.AddonResource
import tv.own.owntv.gambit.addons.model.CatalogExtra
import tv.own.owntv.gambit.addons.net.AddonClient
import tv.own.owntv.gambit.addons.net.AddonError
import tv.own.owntv.gambit.addons.net.AddonUrl

class DiscoveryBoardTest {
    private val server = FakeAddonServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var registry = AddonRegistry()
    private val board = DiscoveryBoard(AddonRepository(AddonClient(server.client)), { registry }, scope)

    @After
    fun tearDown() = scope.cancel()

    private val a = "https://disc-a.example/KEY-A"
    private val b = "https://disc-b.example/KEY-B"

    private fun addon(base: String, id: String, vararg catalogs: AddonCatalog, enabled: Boolean = true) = InstalledAddon(
        AddonUrl.parse("$base/manifest.json")!!,
        AddonManifest(id, "Addon $id", "1.0.0", resources = listOf(AddonResource("catalog")), types = listOf("movie", "series"), catalogs = catalogs.toList()),
        enabled,
        setupId = tv.own.owntv.gambit.addons.TestAddons.setupId("$base|$id"),
    )

    private val paged = listOf(CatalogExtra("skip"))
    private fun cat(type: String, id: String, extra: List<CatalogExtra> = paged) = AddonCatalog(type, id, "Cat $id", extra)

    private fun metas(vararg ids: String) =
        """{"metas":[${ids.joinToString(",") { """{"id":"$it","type":"movie","name":"Title $it"}""" }}]}"""

    private fun ids(feed: DiscoveryFeed) = (feed as? DiscoveryFeed.Loaded)?.items?.map { it.id }.orEmpty()

    private suspend fun await(predicate: (DiscoveryState) -> Boolean): DiscoveryState =
        withTimeout(5_000) { board.state.first(predicate) }

    private suspend fun awaitLoaded(): DiscoveryFeed.Loaded =
        await { it.feed is DiscoveryFeed.Loaded && !(it.feed as DiscoveryFeed.Loaded).loadingMore }.feed as DiscoveryFeed.Loaded

    @Test
    fun `catalogues follow addon order and the type`() = runBlocking {
        registry = AddonRegistry(listOf(
            addon(a, "a", cat("movie", "top"), cat("movie", "new"), cat("series", "shows")),
            addon(b, "b", cat("movie", "pop")),
        ))
        server.onJson("$a/catalog/movie/top.json", metas("t1"))
        board.open()
        assertEquals(listOf("top", "new", "pop"), board.state.value.catalogs.map { it.ref.catalog.id })
        assertEquals("top", board.state.value.selected?.ref?.catalog?.id)
        board.selectType(DiscoveryType.SERIES)
        assertEquals(listOf("shows"), board.state.value.catalogs.map { it.ref.catalog.id })
        assertEquals(DiscoveryType.SERIES, board.state.value.type)
    }

    @Test
    fun `disabled addons and catalogues that need a search or other extra are left out`() = runBlocking {
        registry = AddonRegistry(listOf(
            addon(
                a, "a", cat("movie", "top"), cat("movie", "search", listOf(CatalogExtra("search", isRequired = true))),
                cat("movie", "g", listOf(CatalogExtra("genre", isRequired = true, options = listOf("X")))), // Gambit: a required genre with options is now shown
                cat("movie", "year", listOf(CatalogExtra("year", isRequired = true, options = listOf("2020")))),
                cat("movie", "skip", listOf(CatalogExtra("skip", isRequired = true))),
            ),
            addon(b, "b", cat("movie", "pop"), enabled = false),
        ))
        server.onJson("$a/catalog/movie/top.json", metas("t1"))
        board.open()
        assertEquals(listOf("top", "g"), board.state.value.catalogs.map { it.ref.catalog.id })
    }

    @Test
    fun `first catalogue loads on open`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top"), cat("movie", "new"))))
        server.onJson("$a/catalog/movie/top.json", metas("t1", "t2"))
        board.open()
        assertEquals(DiscoveryFeed.Loading, board.state.value.feed)
        assertEquals(listOf("t1", "t2"), ids(awaitLoaded()))
        assertEquals(listOf("$a/catalog/movie/top.json"), server.requested)
    }

    @Test
    fun `no catalogues means an empty feed and no request`() = runBlocking {
        board.open()
        assertEquals(DiscoveryFeed.Empty, board.state.value.feed)
        assertTrue(board.state.value.catalogs.isEmpty())
        assertTrue(server.requested.isEmpty())
    }

    @Test
    fun `a failing addon does not hide another and a failure is retried`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top")), addon(b, "b", cat("movie", "pop"))))
        server.onJson("$a/catalog/movie/top.json", "{}", code = 500)
        server.onJson("$b/catalog/movie/pop.json", metas("p1"))
        board.open()
        val failed = await { it.feed is DiscoveryFeed.Failed }.feed as DiscoveryFeed.Failed
        assertTrue(failed.error is AddonError.HttpStatus)
        board.selectCatalog(board.state.value.catalogs[1].key)
        assertEquals(listOf("p1"), ids(awaitLoaded()))
        board.selectCatalog(board.state.value.catalogs[0].key)
        await { it.feed is DiscoveryFeed.Failed }
        assertEquals(2, server.requested.count { it.startsWith("$a/") })
    }

    @Test
    fun `an addon with no titles gives an empty feed`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top"))))
        server.onJson("$a/catalog/movie/top.json", metas())
        board.open()
        await { it.feed == DiscoveryFeed.Empty }
        Unit
    }

    @Test
    fun `load more skips what was fetched and stops on an empty page`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top"))))
        server.onJson("$a/catalog/movie/top.json", metas("t1", "t2"))
        server.onJson("$a/catalog/movie/top/skip=2.json", metas("t2", "t3"))
        server.onJson("$a/catalog/movie/top/skip=4.json", metas())
        board.open()
        assertTrue(awaitLoaded().canLoadMore)
        board.loadMore()
        val second = awaitLoaded()
        assertEquals(listOf("t1", "t2", "t3"), second.items.map { it.id })
        assertEquals(4, second.fetched)
        board.loadMore()
        await { (it.feed as? DiscoveryFeed.Loaded)?.let { f -> !f.loadingMore && !f.canLoadMore } == true }
        assertEquals(3, ids(board.state.value.feed).size)
    }

    @Test
    fun `a catalogue without skip is one page`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top", extra = emptyList()))))
        server.onJson("$a/catalog/movie/top.json", metas("t1"))
        board.open()
        assertFalse(awaitLoaded().canLoadMore)
        board.loadMore()
        delay(100)
        assertEquals(1, server.requested.size)
    }

    // Gambit: only an empty page ends paging; repeat pages are followed at once, at most 3 in a row.
    private suspend fun awaitEnded(): DiscoveryFeed.Loaded =
        await { (it.feed as? DiscoveryFeed.Loaded)?.let { f -> !f.loadingMore && !f.canLoadMore } == true }.feed as DiscoveryFeed.Loaded

    @Test
    fun `an addon that ignores skip costs at most three extra requests`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top"))))
        listOf("top.json", "top/skip=2.json", "top/skip=4.json", "top/skip=6.json", "top/skip=8.json").forEach {
            server.onJson("$a/catalog/movie/$it", metas("t1", "t2"))
        }
        board.open()
        awaitLoaded()
        board.loadMore()
        assertEquals(listOf("t1", "t2"), awaitEnded().items.map { it.id })
        board.loadMore()
        delay(100)
        assertEquals(
            listOf("top.json", "top/skip=2.json", "top/skip=4.json", "top/skip=6.json").map { "$a/catalog/movie/$it" },
            server.requested.toList(),
        )
    }

    @Test
    fun `a page of repeats is followed by the next page without a focus change`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top"))))
        server.onJson("$a/catalog/movie/top.json", metas("t1", "t2"))
        server.onJson("$a/catalog/movie/top/skip=2.json", metas("t1", "t2"))
        server.onJson("$a/catalog/movie/top/skip=4.json", metas("t3"))
        board.open()
        awaitLoaded()
        board.loadMore() // one call, as one focus event would make
        val f = await { ids(it.feed).size == 3 && !(it.feed as DiscoveryFeed.Loaded).loadingMore }.feed as DiscoveryFeed.Loaded
        assertEquals(listOf("t1", "t2", "t3"), f.items.map { it.id })
        assertEquals(5, f.fetched) // skip counts repeats too
        assertTrue(f.canLoadMore)
        assertEquals(3, server.requested.size)
    }

    @Test
    fun `three repeat pages in a row end paging`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top"))))
        server.onJson("$a/catalog/movie/top.json", metas("t1", "t2", "t3"))
        server.onJson("$a/catalog/movie/top/skip=3.json", metas("t1"))
        server.onJson("$a/catalog/movie/top/skip=4.json", metas("t2", "t3"))
        server.onJson("$a/catalog/movie/top/skip=6.json", metas("t3"))
        server.onJson("$a/catalog/movie/top/skip=7.json", metas("t9"))
        board.open()
        awaitLoaded()
        board.loadMore()
        val f = awaitEnded()
        assertEquals(listOf("t1", "t2", "t3"), f.items.map { it.id })
        assertEquals(7, f.fetched)
        delay(100)
        assertFalse(server.requested.any { it.endsWith("skip=7.json") })
    }

    @Test
    fun `a short page does not end paging, an empty one after repeats does`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top"))))
        server.onJson("$a/catalog/movie/top.json", metas("t1", "t2"))
        server.onJson("$a/catalog/movie/top/skip=2.json", metas("t3"))
        server.onJson("$a/catalog/movie/top/skip=3.json", metas("t3"))
        server.onJson("$a/catalog/movie/top/skip=4.json", metas())
        board.open()
        awaitLoaded()
        board.loadMore()
        assertTrue(await { ids(it.feed).size == 3 && !(it.feed as DiscoveryFeed.Loaded).loadingMore }.feed.let { (it as DiscoveryFeed.Loaded).canLoadMore })
        board.loadMore()
        assertEquals(listOf("t1", "t2", "t3"), awaitEnded().items.map { it.id })
        board.loadMore()
        delay(100)
        assertEquals(4, server.requested.size)
    }

    @Test
    fun `a failed load more keeps the titles and can be retried`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top"))))
        server.onJson("$a/catalog/movie/top.json", metas("t1"))
        server.onJson("$a/catalog/movie/top/skip=1.json", "{}", code = 503)
        board.open()
        awaitLoaded()
        board.loadMore()
        val f = await { (it.feed as? DiscoveryFeed.Loaded)?.moreError != null }.feed as DiscoveryFeed.Loaded
        assertEquals(listOf("t1"), f.items.map { it.id })
        assertTrue(f.canLoadMore)
        server.onJson("$a/catalog/movie/top/skip=1.json", metas("t2"))
        board.loadMore()
        await { ids(it.feed).size == 2 }
        Unit
    }

    @Test
    fun `genres are offered and sent as the genre extra`() = runBlocking {
        val withGenres = listOf(CatalogExtra("skip"), CatalogExtra("genre", options = listOf("Action", "Drama", "Action", "")))
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top", withGenres), cat("movie", "new"))))
        server.onJson("$a/catalog/movie/top.json", metas("t1"))
        server.onJson("$a/catalog/movie/top/genre=Action.json", metas("g1"))
        server.onJson("$a/catalog/movie/top/genre=Action&skip=1.json", metas("g2"))
        server.onJson("$a/catalog/movie/new.json", metas("n1"))
        board.open()
        awaitLoaded()
        assertEquals(listOf("Action", "Drama"), board.state.value.selected?.genres)
        board.selectGenre("Nope")
        assertEquals(null, board.state.value.genre)
        board.selectGenre("Action")
        await { ids(it.feed) == listOf("g1") }
        board.loadMore()
        await { ids(it.feed) == listOf("g1", "g2") }
        board.selectCatalog(board.state.value.catalogs[1].key)
        await { ids(it.feed) == listOf("n1") }
        assertEquals(null, board.state.value.genre)
    }

    @Test
    fun `going back to a catalogue uses the session cache and open refreshes`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top"), cat("movie", "new"))))
        server.onJson("$a/catalog/movie/top.json", metas("t1"))
        server.onJson("$a/catalog/movie/new.json", metas("n1"))
        board.open()
        awaitLoaded()
        board.selectCatalog(board.state.value.catalogs[1].key)
        await { ids(it.feed) == listOf("n1") }
        board.selectCatalog(board.state.value.catalogs[0].key)
        assertEquals(listOf("t1"), ids(board.state.value.feed))
        assertEquals(2, server.requested.size)
        board.open()
        await { ids(it.feed) == listOf("t1") }
        delay(100)
        assertEquals(3, server.requested.size)
    }

    @Test
    fun `a late reply for an old choice is dropped`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top")), addon(b, "b", cat("movie", "pop"))))
        server.on("$a/catalog/movie/top.json", Reply.Slow(250, metas("slow")))
        server.onJson("$b/catalog/movie/pop.json", metas("fast"))
        board.open()
        board.selectCatalog(board.state.value.catalogs[1].key)
        assertEquals(listOf("fast"), ids(awaitLoaded()))
        delay(500)
        assertEquals(listOf("fast"), ids(board.state.value.feed))
        assertEquals("pop", board.state.value.selected?.ref?.catalog?.id)
    }

    @Test
    fun `removing the chosen addon moves to the first remaining catalogue`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top")), addon(b, "b", cat("movie", "pop"))))
        server.onJson("$a/catalog/movie/top.json", metas("t1"))
        server.onJson("$b/catalog/movie/pop.json", metas("p1"))
        board.onRegistryChanged()
        assertTrue("nothing happens before open", server.requested.isEmpty())
        board.open()
        awaitLoaded()
        registry = AddonRegistry(listOf(addon(b, "b", cat("movie", "pop"))))
        board.onRegistryChanged()
        await { ids(it.feed) == listOf("p1") }
        assertEquals("pop", board.state.value.selected?.ref?.catalog?.id)
        assertEquals(1, board.state.value.catalogs.size)
    }

    @Test
    fun `an unchanged registry does not reload`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top"))))
        server.onJson("$a/catalog/movie/top.json", metas("t1"))
        board.open()
        awaitLoaded()
        board.onRegistryChanged()
        delay(100)
        assertEquals(1, server.requested.size)
    }

    @Test
    fun `no state text carries an addon key`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top"))))
        server.onJson("$a/catalog/movie/top.json", metas("t1"))
        board.open()
        awaitLoaded()
        val text = board.state.value.toString()
        assertNotNull(text)
        assertFalse(text, text.contains("KEY-"))
    }

    @Test
    fun `open can start on a given type and catalogue, Home's row`() = runBlocking {
        registry = AddonRegistry(listOf(
            addon(a, "a", cat("movie", "top"), cat("series", "shows")),
            addon(b, "b", cat("movie", "pop")),
        ))
        server.onJson("$a/catalog/series/shows.json", metas("s1"))
        server.onJson("$b/catalog/movie/pop.json", metas("p1"))
        board.selectType(DiscoveryType.SERIES)
        val popKey = registry.browseCatalogs("movie").first { it.catalog.id == "pop" }.key
        board.open(DiscoveryType.MOVIE, popKey)
        assertEquals(DiscoveryType.MOVIE, board.state.value.type)
        assertEquals("pop", board.state.value.selected?.ref?.catalog?.id)
        assertEquals(listOf("p1"), ids(awaitLoaded()))
        assertFalse(server.requested.any { "/catalog/movie/top" in it })
    }

    @Test
    fun `open with a catalogue no addon offers keeps the usual choice`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top"))))
        server.onJson("$a/catalog/movie/top.json", metas("t1"))
        board.open(DiscoveryType.MOVIE, "gone|nowhere.example|movie|x")
        assertEquals("top", board.state.value.selected?.ref?.catalog?.id)
        assertEquals(listOf("t1"), ids(awaitLoaded()))
    }
}
