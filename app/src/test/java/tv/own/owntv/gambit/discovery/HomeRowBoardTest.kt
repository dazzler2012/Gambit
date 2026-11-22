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
import org.junit.Assert.assertNull
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
import tv.own.owntv.gambit.addons.net.AddonUrl
import java.io.IOException

class HomeRowBoardTest {
    private val server = FakeAddonServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var registry = AddonRegistry()
    private val repository = AddonRepository(AddonClient(server.client))
    private val board = HomeRowBoard(repository, { registry }, scope)

    @After
    fun tearDown() = scope.cancel()

    private val a = "https://home-a.example/KEY-A"
    private val b = "https://home-b.example/KEY-B"

    private fun addon(base: String, id: String, vararg catalogs: AddonCatalog, enabled: Boolean = true) = InstalledAddon(
        AddonUrl.parse("$base/manifest.json")!!,
        AddonManifest(id, "Addon $id", "1.0.0", resources = listOf(AddonResource("catalog")), types = listOf("movie", "series"), catalogs = catalogs.toList()),
        enabled,
    )

    private fun cat(type: String, id: String, name: String? = "Cat $id", extra: List<CatalogExtra> = emptyList()) = AddonCatalog(type, id, name, extra)

    private fun metas(vararg ids: String) =
        """{"metas":[${ids.joinToString(",") { """{"id":"$it","type":"movie","name":"Title $it"}""" }}]}"""

    private suspend fun awaitItems(): HomeRowState = withTimeout(5_000) { board.state.first { it.items.isNotEmpty() } }

    /** Waits until no refresh is running and the board has settled. */
    private suspend fun settle() {
        delay(300)
    }

    @Test
    fun `the row is fed by the first movie catalogue in addon order`() = runBlocking {
        registry = AddonRegistry(listOf(
            addon(a, "a", cat("series", "shows"), cat("movie", "top", "Popular")),
            addon(b, "b", cat("movie", "pop")),
        ))
        server.onJson("$a/catalog/series/shows.json", metas("s1"))
        server.onJson("$a/catalog/movie/top.json", metas("t1", "t2"))
        server.onJson("$b/catalog/movie/pop.json", metas("p1"))
        board.refresh()
        val row = awaitItems()
        assertEquals("top", row.catalog?.ref?.catalog?.id)
        assertEquals("Popular", row.catalog?.title)
        assertEquals(listOf("t1", "t2"), row.items.map { it.id })
        assertFalse(server.requested.any { "/catalog/series/" in it })
        assertFalse(server.requested.any { it.startsWith(b) })
    }

    @Test
    fun `a failing or empty catalogue falls through to the next`() = runBlocking {
        registry = AddonRegistry(listOf(
            addon(a, "a", cat("movie", "down"), cat("movie", "empty")),
            addon(b, "b", cat("movie", "pop")),
        ))
        server.on("$a/catalog/movie/down.json", Reply.Fail(IOException("offline")))
        server.onJson("$a/catalog/movie/empty.json", metas())
        server.onJson("$b/catalog/movie/pop.json", metas("p1"))
        board.refresh()
        val row = awaitItems()
        assertEquals("pop", row.catalog?.ref?.catalog?.id)
        assertEquals("Addon b", row.catalog?.addonName)
    }

    @Test
    fun `only three catalogues are tried, then the row is empty`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "c1"), cat("movie", "c2"), cat("movie", "c3"), cat("movie", "c4"))))
        server.onJson("$a/catalog/movie/c4.json", metas("x"))
        board.refresh()
        settle()
        assertTrue(board.state.value.items.isEmpty())
        assertNull(board.state.value.catalog)
        assertFalse(server.requested.any { "/c4" in it })
        assertEquals(3, server.requested.count { "/catalog/movie/" in it })
    }

    @Test
    fun `disabled addons and catalogues that need an extra are skipped`() = runBlocking {
        registry = AddonRegistry(listOf(
            addon(a, "a", cat("movie", "off"), enabled = false),
            addon(b, "b", cat("movie", "search", extra = listOf(CatalogExtra("search", isRequired = true))), cat("movie", "ok")),
        ))
        server.onJson("$b/catalog/movie/ok.json", metas("o1"))
        board.refresh()
        assertEquals("ok", awaitItems().catalog?.ref?.catalog?.id)
        assertFalse(server.requested.any { it.startsWith(a) })
    }

    @Test
    fun `no movie catalogue leaves the row empty without a request`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("series", "shows"))))
        board.refresh()
        settle()
        assertTrue(board.state.value.items.isEmpty())
        assertTrue(server.requested.isEmpty())
    }

    @Test
    fun `repeats are dropped and the row stops at twenty`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "big"))))
        val ids = (1..30).map { "m$it" }
        server.onJson("$a/catalog/movie/big.json", metas("m1", "m1", *ids.toTypedArray()))
        board.refresh()
        val row = awaitItems()
        assertEquals(HomeRowBoard.MAX_ITEMS, row.items.size)
        assertEquals(ids.take(HomeRowBoard.MAX_ITEMS), row.items.map { it.id })
    }

    @Test
    fun `posters stay while a refresh is on its way`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top"))))
        server.onJson("$a/catalog/movie/top.json", metas("t1"))
        board.refresh()
        awaitItems()
        server.on("$a/catalog/movie/top.json", Reply.Slow(400, metas("t2")))
        board.refresh()
        delay(100)
        assertEquals(listOf("t1"), board.state.value.items.map { it.id })
        withTimeout(5_000) { board.state.first { s -> s.items.map { it.id } == listOf("t2") } }
        Unit
    }

    @Test
    fun `clear empties the row and drops a late answer`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top"))))
        server.on("$a/catalog/movie/top.json", Reply.Slow(300, metas("t1")))
        board.refresh()
        delay(50)
        board.clear()
        delay(600)
        assertTrue(board.state.value.items.isEmpty())
    }

    @Test
    fun `nothing in the row's state holds an addon address`() = runBlocking {
        registry = AddonRegistry(listOf(addon(a, "a", cat("movie", "top"))))
        server.onJson("$a/catalog/movie/top.json", metas("t1"))
        board.refresh()
        val text = awaitItems().toString()
        assertFalse(text.contains("KEY-A"))
    }
}
