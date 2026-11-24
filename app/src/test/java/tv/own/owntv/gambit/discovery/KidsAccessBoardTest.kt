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
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.FakeAddonServer
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.data.InstalledAddon
import tv.own.owntv.gambit.addons.model.AddonCatalog
import tv.own.owntv.gambit.addons.model.AddonManifest
import tv.own.owntv.gambit.addons.model.AddonResource
import tv.own.owntv.gambit.addons.model.CatalogExtra
import tv.own.owntv.gambit.addons.net.AddonClient
import tv.own.owntv.gambit.addons.net.AddonUrl
import tv.own.owntv.gambit.addons.net.CatalogExtraArgs

/** Kids addon access on the boards and the title gate (tests 16–27). Fake `*.example` hosts only. */
class KidsAccessBoardTest {
    private val server = FakeAddonServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository = AddonRepository(AddonClient(server.client))
    private val gate = KidsTitleGate()
    private val a = "https://kids-a.example/KEY-A"

    private val paged = listOf(CatalogExtra("skip"))
    private val withGenre = paged + CatalogExtra("genre", options = listOf("Action"))

    private fun cat(type: String, id: String, extra: List<CatalogExtra> = paged) = AddonCatalog(type, id, "Cat $id", extra)

    private val all = AddonRegistry(listOf(
        InstalledAddon(
            AddonUrl.parse("$a/manifest.json")!!,
            AddonManifest(
                "a", "Addon a", "1.0.0", resources = listOf(AddonResource("catalog")), types = listOf("movie", "series"),
                catalogs = listOf(cat("movie", "top", withGenre), cat("movie", "new"), cat("series", "shows")),
            ),
            setupId = tv.own.owntv.gambit.addons.TestAddons.setupId("a"),
        ),
    ))

    private fun key(id: String) = all.browseCatalogs().first { it.catalog.id == id }.key

    private var approved: Set<String> = emptySet()
    private fun approve(vararg ids: String, profile: Long = 1) {
        approved = ids.map(::key).toSet()
        gate.retainApproved(profile, approved)
    }

    private fun board(kids: Boolean = true) = DiscoveryBoard(repository, { all.limitedTo(approved) }, scope, gate, { kids })
    private fun home() = HomeRowBoard(repository, { all.limitedTo(approved) }, scope, gate)

    @After
    fun tearDown() = scope.cancel()

    private fun metas(vararg ids: String) =
        """{"metas":[${ids.joinToString(",") { """{"id":"$it","type":"movie","name":"Title $it"}""" }}]}"""

    private suspend fun DiscoveryBoard.loaded(): DiscoveryFeed.Loaded =
        withTimeout(5_000) { state.first { it.feed is DiscoveryFeed.Loaded && !(it.feed as DiscoveryFeed.Loaded).loadingMore } }.feed as DiscoveryFeed.Loaded

    // --- 16. only approved catalogues -----------------------------------------------------------

    @Test
    fun `only approved catalogues are listed and a stale key drops out`() = runBlocking {
        approve("new", "shows")
        approved = approved + "gone|kids-a.example|movie|stale"
        val b = board()
        b.open()
        assertEquals(listOf("new"), b.state.value.catalogs.map { it.ref.catalog.id })
        b.selectType(DiscoveryType.SERIES)
        assertEquals(listOf("shows"), b.state.value.catalogs.map { it.ref.catalog.id })
    }

    // --- 17. no genres ---------------------------------------------------------------------------

    @Test
    fun `kids have no genre options and selectGenre sends no request`() = runBlocking {
        approve("top")
        server.onJson("$a/catalog/movie/top.json", metas("t1"))
        val b = board()
        b.open()
        b.loaded()
        assertTrue(b.state.value.selected!!.genres.isEmpty())
        val before = server.requested.toList()
        b.selectGenre("Action")
        delay(200)
        assertEquals(null, b.state.value.genre)
        assertEquals(before, server.requested.toList())
        // The same catalogue does offer its genre to an adult board.
        assertEquals(listOf("Action"), board(kids = false).also { it.open() }.state.value.selected!!.genres)
    }

    // --- 18, 19. paging works, search is never offered ----------------------------------------------

    @Test
    fun `paging with skip works and nothing asks for a genre or a search`() = runBlocking {
        approve("top")
        server.onJson("$a/catalog/movie/top.json", metas("t1", "t2"))
        server.onJson("$a/catalog/movie/top/skip=2.json", metas("t3"))
        val b = board()
        b.open()
        b.loaded()
        b.loadMore()
        withTimeout(5_000) { b.state.first { (it.feed as? DiscoveryFeed.Loaded)?.items?.size == 3 } }
        assertEquals(listOf("$a/catalog/movie/top.json", "$a/catalog/movie/top/skip=2.json"), server.requested.toList())
        assertTrue(server.requested.none { "search=" in it || "genre=" in it })
        assertTrue(gate.allows(key("top"), "t3"))
    }

    @Test
    fun `a kids board follows a repeat page and reports every page to the gate`() = runBlocking {
        approve("top")
        server.onJson("$a/catalog/movie/top.json", metas("t1", "t2"))
        server.onJson("$a/catalog/movie/top/skip=2.json", metas("t2", "r1"))
        val b = board()
        b.open()
        b.loaded()
        b.loadMore()
        withTimeout(5_000) { b.state.first { (it.feed as? DiscoveryFeed.Loaded)?.items?.size == 3 } }
        // A page with one new title is not a repeat page: paging waits for focus again.
        delay(100)
        assertEquals(listOf("$a/catalog/movie/top.json", "$a/catalog/movie/top/skip=2.json"), server.requested.toList())
        server.onJson("$a/catalog/movie/top/skip=4.json", metas("t1", "r1"))
        server.onJson("$a/catalog/movie/top/skip=6.json", metas("t5"))
        b.loadMore()
        withTimeout(5_000) { b.state.first { (it.feed as? DiscoveryFeed.Loaded)?.items?.size == 4 } }
        assertTrue(server.requested.none { "search=" in it || "genre=" in it })
        listOf("t1", "t2", "r1", "t5").forEach { assertTrue(it, gate.allows(key("top"), it)) }
    }

    // --- 20. which requests may add ids ------------------------------------------------------------

    @Test
    fun `titles from a genre or search request are refused, paged ones are allowed`() {
        approve("top")
        gate.report(key("top"), listOf("g1"), CatalogExtraArgs(genre = "Action"))
        gate.report(key("top"), listOf("s1"), CatalogExtraArgs(search = "x"))
        gate.report(key("top"), listOf("p1"), CatalogExtraArgs(skip = 20))
        gate.report(key("top"), listOf("d1"), CatalogExtraArgs())
        assertFalse(gate.allows(key("top"), "g1"))
        assertFalse(gate.allows(key("top"), "s1"))
        assertTrue(gate.allows(key("top"), "p1"))
        assertTrue(gate.allows(key("top"), "d1"))
    }

    @Test
    fun `only a request with no extras, or skip alone, adds ids`() {
        approve("top")
        gate.report(key("top"), listOf("e1"), CatalogExtraArgs(search = ""))
        gate.report(key("top"), listOf("e2"), CatalogExtraArgs(genre = ""))
        gate.report(key("top"), listOf("e3"), CatalogExtraArgs(skip = 40, genre = "Action"))
        gate.report(key("top"), listOf("e4"), CatalogExtraArgs(skip = 40, search = "x"))
        listOf("e1", "e2", "e3", "e4").forEach { assertFalse(it, gate.allows(key("top"), it)) }
        gate.report(key("top"), listOf("ok"), CatalogExtraArgs(skip = 40))
        assertTrue(gate.allows(key("top"), "ok"))
    }

    @Test
    fun `an adult browsing an approved catalogue adds nothing to the gate`() = runBlocking {
        approve("new")
        server.onJson("$a/catalog/movie/new.json", metas("n1"))
        server.onJson("$a/catalog/movie/new/skip=1.json", metas("n2"))
        val adult = board(kids = false)
        adult.open()
        adult.loaded()
        adult.loadMore()
        withTimeout(5_000) { adult.state.first { (it.feed as? DiscoveryFeed.Loaded)?.items?.size == 2 } }
        assertFalse(gate.allows(key("new"), "n1"))
        assertFalse(gate.allows(key("new"), "n2"))

        val adultRow = HomeRowBoard(repository, { all.limitedTo(approved) }, scope, gate) { false }
        adultRow.refresh()
        withTimeout(5_000) { adultRow.state.first { it.items.isNotEmpty() } }
        assertFalse(gate.allows(key("new"), "n1"))
    }

    // --- 21, 22. unapproved catalogues ---------------------------------------------------------------

    @Test
    fun `an id from an unapproved catalogue is refused`() {
        approve("top")
        gate.report(key("new"), listOf("n1"), CatalogExtraArgs())
        assertFalse(gate.allows(key("new"), "n1"))
        gate.report(key("top"), listOf("t1"), CatalogExtraArgs())
        assertFalse("an id is only good for the catalogue it came from", gate.allows(key("new"), "t1"))
        assertFalse(KidsTitleSession(gate).open(key("new"), "t1"))
    }

    @Test
    fun `opening from Home with an unapproved catalogue is refused`() = runBlocking {
        approve("new")
        server.onJson("$a/catalog/movie/new.json", metas("n1"))
        val row = home()
        row.refresh()
        withTimeout(5_000) { row.state.first { it.items.isNotEmpty() } }
        val session = KidsTitleSession(gate)
        assertTrue(session.open(key("new"), "n1"))
        assertFalse(session.open(key("top"), "n1"))
        assertFalse(session.open(key("new"), "never-listed"))
    }

    // --- 23. restart --------------------------------------------------------------------------------

    @Test
    fun `after a restart a known id is refused until the approved catalogue loads again`() = runBlocking {
        approve("new")
        server.onJson("$a/catalog/movie/new.json", metas("n1"))
        board().also { it.open(); it.loaded() }
        assertTrue(gate.allows(key("new"), "n1"))

        // Restart: a fresh gate and board, the approvals are still stored.
        val fresh = KidsTitleGate()
        fresh.retainApproved(1, approved)
        assertFalse(fresh.allows(key("new"), "n1"))
        val b = DiscoveryBoard(repository, { all.limitedTo(approved) }, scope, fresh) { true }
        b.open()
        b.loaded()
        assertTrue(fresh.allows(key("new"), "n1"))
    }

    // --- 24. approvals change --------------------------------------------------------------------------

    @Test
    fun `after the approvals change ids from a removed catalogue are refused`() {
        approve("top", "new")
        gate.report(key("top"), listOf("t1"), CatalogExtraArgs())
        gate.report(key("new"), listOf("n1"), CatalogExtraArgs())
        approve("new")
        assertFalse(gate.allows(key("top"), "t1"))
        assertTrue(gate.allows(key("new"), "n1"))
        approve("top", "new") // approving it again does not bring the old ids back
        assertFalse(gate.allows(key("top"), "t1"))
    }

    @Test
    fun `a profile switch or no kids profile forgets every id`() {
        approve("new", profile = 1)
        gate.report(key("new"), listOf("n1"), CatalogExtraArgs())
        approve("new", profile = 2)
        assertFalse(gate.allows(key("new"), "n1"))
        gate.report(key("new"), listOf("n2"), CatalogExtraArgs())
        gate.reset()
        assertFalse(gate.allows(key("new"), "n2"))
    }

    // --- 25. streams and playback ------------------------------------------------------------------------

    @Test
    fun `streams need a gated title and follow its catalogue, episodes of the open series included`() {
        approve("shows")
        val session = KidsTitleSession(gate)
        assertFalse("no title open", session.mayStream())
        assertFalse(session.open(key("shows"), "tt1"))
        assertFalse(session.mayStream())

        gate.report(key("shows"), listOf("tt1"), CatalogExtraArgs())
        assertTrue(session.open(key("shows"), "tt1"))
        assertTrue(session.mayStream())
        assertTrue(session.mayStreamEpisode(listOf("tt1:1:1", "tt1:1:2"), "tt1:1:2"))
        assertFalse("not an episode of this page", session.mayStreamEpisode(listOf("tt1:1:1"), "tt9:1:1"))
        assertFalse("page not loaded yet", session.mayStreamEpisode(null, "tt1:1:1"))

        approve("new") // the series catalogue is no longer approved
        assertFalse(session.mayStream())
        approve("shows")
        assertFalse("its ids are gone", session.mayStream())

        gate.report(key("shows"), listOf("tt1"), CatalogExtraArgs())
        assertTrue(session.open(key("shows"), "tt1"))
        session.close()
        assertFalse(session.mayStream())
    }

    // --- 26, 27. Home row --------------------------------------------------------------------------------

    @Test
    fun `the first approved movies catalogue feeds the Home row`() = runBlocking {
        approve("new", "shows") // "top" comes first in addon order but is not approved
        server.onJson("$a/catalog/movie/new.json", metas("n1", "n2"))
        val row = home()
        row.refresh()
        val state = withTimeout(5_000) { row.state.first { it.items.isNotEmpty() } }
        assertEquals("new", state.catalog?.ref?.catalog?.id)
        assertEquals(listOf("n1", "n2"), state.items.map { it.id })
        assertTrue(gate.allows(key("new"), "n2"))
        assertTrue(server.requested.none { "/catalog/movie/top" in it || "/catalog/series/" in it })
    }

    @Test
    fun `with only series approved the Home row is hidden and Discovery lists the series`() = runBlocking {
        approve("shows")
        val row = home()
        row.refresh()
        delay(300)
        assertTrue(row.state.value.items.isEmpty())
        assertTrue(server.requested.isEmpty())

        server.onJson("$a/catalog/series/shows.json", metas("s1"))
        val b = board()
        b.open()
        b.selectType(DiscoveryType.SERIES)
        assertEquals(listOf("s1"), b.loaded().items.map { it.id })
        assertTrue(gate.allows(key("shows"), "s1"))
    }
}
