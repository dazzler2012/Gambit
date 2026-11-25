package tv.own.owntv.gambit.discovery

import java.io.IOException
import java.util.concurrent.TimeUnit
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.FakeAddonServer
import tv.own.owntv.gambit.addons.FakeAddonServer.Reply
import tv.own.owntv.gambit.addons.TestAddons
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.data.InstalledAddon
import tv.own.owntv.gambit.addons.model.AddonCatalog
import tv.own.owntv.gambit.addons.model.AddonManifest
import tv.own.owntv.gambit.addons.model.AddonResource
import tv.own.owntv.gambit.addons.model.CatalogExtra
import tv.own.owntv.gambit.addons.net.AddonClient
import tv.own.owntv.gambit.addons.net.AddonUrl
import tv.own.owntv.gambit.ui.discovery.AddonAccess
import tv.own.owntv.gambit.ui.discovery.registryFrom
import tv.own.owntv.gambit.ui.discovery.searchAllowed

class SearchBoardTest {
    private val server = FakeAddonServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var registry = AddonRegistry()

    @Volatile private var access: AddonAccess = AddonAccess.Adult

    private fun board(debounceMs: Long = NEVER) =
        SearchBoard(AddonRepository(AddonClient(server.client)), { registry }, scope, { searchAllowed(access) }, debounceMs)

    @After
    fun tearDown() = scope.cancel()

    private val a = "https://search-a.example/KEY-A"
    private val b = "https://search-b.example/KEY-B"

    private fun addon(base: String, id: String, vararg catalogs: AddonCatalog, enabled: Boolean = true) = InstalledAddon(
        AddonUrl.parse("$base/manifest.json")!!,
        AddonManifest(id, "Addon $id", "1.0.0", resources = listOf(AddonResource("catalog")), types = listOf("movie", "series"), catalogs = catalogs.toList()),
        enabled,
        setupId = TestAddons.setupId("$base|$id"),
    )

    private val searchable = listOf(CatalogExtra("search", isRequired = true))
    private fun cat(type: String, id: String, extra: List<CatalogExtra> = searchable) = AddonCatalog(type, id, "Cat $id", extra)

    private fun url(base: String, type: String, id: String, term: String) = "$base/catalog/$type/$id/search=$term.json"

    private fun metas(vararg ids: String) =
        """{"metas":[${ids.joinToString(",") { """{"id":"$it","type":"movie","name":"Title $it"}""" }}]}"""

    private fun twoAddons() {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "find")), addon(b, "b", cat("movie", "find"))))
    }

    private suspend fun SearchBoard.await(predicate: (SearchFeed.Results) -> Boolean): SearchFeed.Results =
        withTimeout(5_000) {
            state.first { s -> (s.feed as? SearchFeed.Results)?.let(predicate) == true }.feed as SearchFeed.Results
        }

    private suspend fun SearchBoard.awaitDone(): SearchFeed.Results = await { !it.searching }

    private fun SearchBoard.lookFor(text: String) {
        type(text)
        submit()
    }

    private fun SearchFeed.Results.ids() = visible.map { row -> (row.feed as? SearchRowFeed.Loaded)?.items?.map { it.id }.orEmpty() }

    @Test
    fun `only searchable catalogues are asked, movies then series then other types`() = runBlocking {
        registry = AddonRegistry(listOf(
            addon(
                a, "a", cat("anime", "x"), cat("series", "s"), cat("movie", "m"),
                cat("movie", "plain", listOf(CatalogExtra("skip"))),
                cat("movie", "needsMore", searchable + CatalogExtra("genre", isRequired = true, options = listOf("X"))),
                cat("movie", "optional", listOf(CatalogExtra("search"))),
            ),
            addon(b, "b", cat("movie", "m2"), cat("series", "s2")),
            addon("https://search-off.example/KEY-OFF", "off", cat("movie", "m3"), enabled = false),
        ))
        val board = board()
        board.lookFor("matrix")
        val done = board.awaitDone()
        assertEquals(listOf("m", "optional", "m2", "s", "s2", "x"), done.rows.map { it.ref.catalog.id })
        assertEquals(6, server.requested.size)
        assertTrue(server.requested.all { it.endsWith("/search=matrix.json") })
    }

    @Test
    fun `nothing is asked below the minimum length`() = runBlocking {
        twoAddons()
        val board = board(debounceMs = 0)
        board.lookFor(" a ")
        delay(100)
        assertEquals(SearchFeed.Idle, board.state.value.feed)
        assertTrue(server.requested.isEmpty())
    }

    @Test
    fun `two letters are enough and the text is trimmed and encoded`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "find"))))
        val board = board()
        board.lookFor("  Up ")
        board.awaitDone()
        board.lookFor("the end")
        board.awaitDone()
        assertEquals(listOf(url(a, "movie", "find", "Up"), url(a, "movie", "find", "the%20end")), server.requested.toList())
    }

    @Test
    fun `typing waits for a pause and asks for the last text only`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "find"))))
        val board = board(debounceMs = 300)
        board.type("ma")
        board.type("mat")
        board.type("matr")
        assertTrue(server.requested.isEmpty())
        board.awaitDone()
        assertEquals(listOf(url(a, "movie", "find", "matr")), server.requested.toList())
    }

    @Test
    fun `submit searches at once, and the same text is not asked twice`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "find"))))
        server.onJson(url(a, "movie", "find", "matrix"), metas("m1"))
        val board = board()
        board.type("matrix")
        delay(100)
        assertTrue(server.requested.isEmpty())
        board.submit()
        board.awaitDone()
        board.submit()
        board.type("matrix ")
        board.submit()
        delay(100)
        assertEquals(1, server.requested.size)
        assertEquals(listOf(listOf("m1")), (board.state.value.feed as SearchFeed.Results).ids())
    }

    @Test
    fun `new text drops the old requests and a late reply is ignored`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "find"))))
        val gate = Reply.Gate(metas("old"))
        server.on(url(a, "movie", "find", "first"), gate)
        server.onJson(url(a, "movie", "find", "second"), metas("new"))
        val board = board()
        board.lookFor("first")
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        board.lookFor("second")
        board.awaitDone()
        gate.release.countDown()
        delay(100)
        assertEquals(listOf(listOf("new")), (board.state.value.feed as SearchFeed.Results).ids())
    }

    @Test
    fun `a slow addon does not hold up the others, and rows keep their order`() = runBlocking {
        twoAddons()
        val gate = Reply.Gate(metas("a1"))
        server.on(url(a, "movie", "find", "matrix"), gate)
        server.onJson(url(b, "movie", "find", "matrix"), metas("b1"))
        val board = board()
        board.lookFor("matrix")
        val early = board.await { it.rows[1].feed is SearchRowFeed.Loaded }
        assertEquals(SearchRowFeed.Loading, early.rows[0].feed)
        assertTrue(early.searching)
        assertEquals(2, early.visible.size)
        gate.release.countDown()
        assertEquals(listOf(listOf("a1"), listOf("b1")), board.awaitDone().ids())
    }

    @Test
    fun `a failing addon is named without its address and the others still show`() = runBlocking {
        twoAddons()
        server.onJson(url(a, "movie", "find", "matrix"), """{"err":"boom"}""", 500)
        server.onJson(url(b, "movie", "find", "matrix"), metas("b1"))
        val board = board()
        board.lookFor("matrix")
        val done = board.awaitDone()
        assertEquals(listOf("Addon a"), done.failedAddons)
        assertEquals(listOf(listOf("b1")), done.ids())
        assertFalse(done.allFailed)
        assertFalse(done.nothingFound)
        assertEquals(2, server.requested.size) // a 500 is not retried
    }

    @Test
    fun `the catalogue retry rule applies, then retry asks only the failed addons`() = runBlocking {
        twoAddons()
        val down = Reply.Fail(IOException("down"))
        server.on(url(a, "movie", "find", "matrix"), Reply.Sequence(listOf(down, down, Reply.Body(metas("a1")))))
        server.onJson(url(b, "movie", "find", "matrix"), metas("b1"))
        val board = board()
        board.lookFor("matrix")
        assertEquals(listOf("Addon a"), board.awaitDone().failedAddons)
        assertEquals(3, server.requested.size) // a twice, b once
        board.retryFailed()
        val done = board.await { !it.searching && it.failedAddons.isEmpty() }
        assertEquals(listOf(listOf("a1"), listOf("b1")), done.ids())
        assertEquals(4, server.requested.size)
    }

    @Test
    fun `nothing found, all failed and no searchable addon are told apart`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "find"))))
        server.onJson(url(a, "movie", "find", "none"), metas())
        val board = board()
        board.lookFor("none")
        val empty = board.awaitDone()
        assertTrue(empty.nothingFound)
        assertFalse(empty.allFailed)
        board.lookFor("broken") // unrouted: 404
        val failed = board.awaitDone()
        assertTrue(failed.allFailed)
        assertFalse(failed.nothingFound)
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "plain", emptyList()))))
        board.lookFor("other")
        assertEquals(SearchFeed.NoSearchAddons, board.state.value.feed)
    }

    @Test
    fun `repeated titles are dropped and a row is capped`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "find"), cat("movie", "find"))))
        val ids = listOf("t0") + (0..60).map { "t$it" }
        server.onJson(url(a, "movie", "find", "many"), metas(*ids.toTypedArray()))
        val board = board()
        board.lookFor("many")
        val done = board.awaitDone()
        assertEquals(1, done.rows.size)
        assertEquals((0 until SearchBoard.MAX_PER_ROW).map { "t$it" }, done.ids().single())
    }

    @Test
    fun `a picked result carries the addon it came from`() = runBlocking {
        twoAddons()
        server.onJson(url(b, "movie", "find", "matrix"), metas("b1"))
        val board = board()
        board.lookFor("matrix")
        val done = board.awaitDone()
        val pick = board.pick(done.rows[1].key, "b1")
        assertEquals("b1", pick?.preview?.id)
        assertEquals(registry.all[1].url, pick?.source)
        assertNull(board.pick(done.rows[0].key, "b1"))
        assertNull(board.pick(done.rows[1].key, "unknown"))
    }

    @Test
    fun `removing an addon asks again without it`() = runBlocking {
        twoAddons()
        val board = board()
        board.lookFor("matrix")
        board.awaitDone()
        registry = AddonRegistry(listOf(registry.all[1]))
        board.onRegistryChanged()
        assertEquals(1, board.awaitDone().rows.size)
        assertEquals(3, server.requested.size)
    }

    @Test
    fun `every access but Adult is refused with no request`() = runBlocking {
        twoAddons()
        val key = registry.searchCatalogs().first().key
        for (refused in listOf(AddonAccess.Kids(setOf(key), profileId = 2), AddonAccess.Closed, AddonAccess.Unknown)) {
            assertFalse(searchAllowed(refused))
            access = refused
            val board = board(debounceMs = 0)
            board.lookFor("matrix")
            board.retryFailed()
            board.onRegistryChanged()
            delay(50)
            assertEquals("", board.state.value.query)
            assertEquals(SearchFeed.Idle, board.state.value.feed)
            assertNull(board.pick(key, "any"))
        }
        assertTrue(server.requested.isEmpty())
        assertTrue(searchAllowed(AddonAccess.Adult))
    }

    @Test
    fun `a kids registry has nothing to search even if asked`() = runBlocking {
        twoAddons()
        val kids = AddonAccess.Kids(registry.searchCatalogs().map { it.key }.toSet(), profileId = 2)
        val board = SearchBoard(AddonRepository(AddonClient(server.client)), { kids.registryFrom(registry) }, scope, { true }, NEVER)
        board.lookFor("matrix")
        assertEquals(SearchFeed.NoSearchAddons, board.state.value.feed)
        assertTrue(server.requested.isEmpty())
    }

    @Test
    fun `leaving Adult mid-search forgets the text and results`() = runBlocking {
        twoAddons()
        val gate = Reply.Gate(metas("a1"))
        server.on(url(a, "movie", "find", "matrix"), gate)
        server.onJson(url(b, "movie", "find", "matrix"), metas("b1"))
        val board = board()
        board.lookFor("matrix")
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        val key = board.await { it.rows[1].feed is SearchRowFeed.Loaded }.rows[1].key
        access = AddonAccess.Closed
        assertNull(board.pick(key, "b1"))
        board.accessChanged()
        gate.release.countDown()
        delay(100)
        assertEquals("", board.state.value.query)
        assertEquals(SearchFeed.Idle, board.state.value.feed)
    }

    @Test
    fun `search results never reach the kids title gate`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "find", listOf(CatalogExtra("search"))))))
        val key = registry.searchCatalogs().single().key
        val gate = KidsTitleGate()
        gate.retainApproved(2, setOf(key))
        server.onJson(url(a, "movie", "find", "matrix"), metas("m1"))
        val board = board()
        board.lookFor("matrix")
        board.awaitDone()
        assertNotNull(board.pick(key, "m1"))
        assertFalse(gate.allows(key, "m1"))
    }

    @Test
    fun `no text form holds the typed text or an address key`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "find"))))
        server.onJson(url(a, "movie", "find", "zebra"), metas("zebra1"))
        val board = board()
        board.lookFor("zebra")
        val done = board.awaitDone()
        val pick = board.pick(done.rows.single().key, "zebra1")
        val shown = listOf(board.state.value, board.state.value.feed, done, done.rows.single(), done.rows.single().feed, pick).joinToString()
        assertFalse(shown.contains("zebra"))
        assertFalse(shown.contains("KEY-A"))
    }

    private companion object {
        /** A debounce no test reaches, so only submit() searches. */
        const val NEVER = 60_000L
    }
}
