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

/** Discovery lists every catalogue type, and catalogues whose required extra is a genre. Fake `*.example` hosts only. */
class DiscoverAllTypesTest {
    private val server = FakeAddonServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var registry = AddonRegistry()
    private val board = DiscoveryBoard(AddonRepository(AddonClient(server.client)), { registry }, scope)
    private val base = "https://types.example/KEY-T"

    @After
    fun tearDown() = scope.cancel()

    private fun addon(vararg catalogs: AddonCatalog, enabled: Boolean = true, id: String = "t", host: String = base) = InstalledAddon(
        AddonUrl.parse("$host/manifest.json")!!,
        AddonManifest(id, "Addon $id", "1.0.0", resources = listOf(AddonResource("catalog")), catalogs = catalogs.toList()),
        enabled,
        setupId = tv.own.owntv.gambit.addons.TestAddons.setupId("$host|$id"),
    )

    private fun cat(type: String, id: String, extra: List<CatalogExtra> = emptyList()) = AddonCatalog(type, id, "Cat $id", extra)
    private val genres = listOf("Action", "Drama")
    private fun requiredGenre(options: List<String> = genres) = listOf(CatalogExtra("genre", isRequired = true, options = options))

    private fun metas(vararg ids: String) =
        """{"metas":[${ids.joinToString(",") { """{"id":"$it","type":"movie","name":"Title $it"}""" }}]}"""

    // --- registry ------------------------------------------------------------------------------

    @Test
    fun `types are movie and series first, then others in first-seen order without repeats`() {
        val r = AddonRegistry(listOf(
            addon(cat("Trakt", "l1"), cat("movie", "m"), cat("collection", "c"), cat("Trakt", "l2"), cat("anime.movie", "a")),
        ))
        assertEquals(listOf("movie", "series", "Trakt", "collection", "anime.movie"), r.discoverTypes())
    }

    @Test
    fun `no catalogues still gives movie and series`() {
        assertEquals(listOf("movie", "series"), AddonRegistry().discoverTypes())
        assertEquals(listOf("movie", "series"), AddonRegistry(listOf(addon(enabled = false))).discoverTypes())
    }

    @Test
    fun `types of a disabled addon are not listed`() {
        val r = AddonRegistry(listOf(addon(cat("Trakt", "l1"), enabled = false)))
        assertEquals(listOf("movie", "series"), r.discoverTypes())
    }

    @Test
    fun `extra types are capped`() {
        val many = (1..30).map { cat("t$it", "c$it") }.toTypedArray()
        val types = AddonRegistry(listOf(addon(*many))).discoverTypes()
        assertEquals(22, types.size)
        assertEquals(listOf("movie", "series", "t1"), types.take(3))
    }

    @Test
    fun `required genre with options is discoverable but not browsable`() {
        val r = AddonRegistry(listOf(addon(cat("movie", "g", requiredGenre()), cat("movie", "plain"))))
        assertEquals(listOf("g", "plain"), r.discoverCatalogs("movie").map { it.catalog.id })
        assertEquals(listOf("plain"), r.browseCatalogs("movie").map { it.catalog.id })
    }

    @Test
    fun `other required extras stay hidden`() {
        val r = AddonRegistry(listOf(addon(
            cat("movie", "search", listOf(CatalogExtra("search", isRequired = true))),
            cat("movie", "skip", listOf(CatalogExtra("skip", isRequired = true))),
            cat("movie", "genreNoOptions", requiredGenre(emptyList())),
            cat("movie", "genreBlankOptions", requiredGenre(listOf(" "))),
            cat("movie", "custom", listOf(CatalogExtra("year", isRequired = true, options = listOf("2020")))),
            cat("movie", "genrePlusSearch", requiredGenre() + CatalogExtra("search", isRequired = true)),
            cat("movie", "ok", listOf(CatalogExtra("genre", options = genres), CatalogExtra("skip"))),
        )))
        assertEquals(listOf("ok"), r.discoverCatalogs("movie").map { it.catalog.id })
        assertEquals(listOf("movie", "series"), r.discoverTypes())
    }

    @Test
    fun `browseCatalogs is unchanged for the same data`() {
        val r = AddonRegistry(listOf(addon(cat("Trakt", "l1"), cat("movie", "g", requiredGenre()), cat("movie", "plain"))))
        assertEquals(listOf("l1", "plain"), r.browseCatalogs().map { it.catalog.id })
    }

    // --- label ---------------------------------------------------------------------------------

    @Test
    fun `readable names`() {
        assertEquals("Trakt", DiscoveryType("Trakt").readableName)
        assertEquals("Trakt", DiscoveryType("trakt").readableName)
        assertEquals("Anime Movie", DiscoveryType("anime.movie").readableName)
        assertEquals("Person", DiscoveryType("person").readableName)
        assertEquals("My Lists", DiscoveryType("my_lists").readableName)
        assertEquals("...", DiscoveryType("...").readableName)
    }

    // --- board ---------------------------------------------------------------------------------

    @Test
    fun `a custom type has its own tab and loads its catalogue`() = runBlocking {
        registry = AddonRegistry(listOf(addon(cat("movie", "m"), cat("Trakt", "mine"))))
        server.onJson("$base/catalog/Trakt/mine.json", metas("x1"))
        board.open()
        assertEquals(listOf("movie", "series", "Trakt"), board.state.value.types.map { it.id })
        board.selectType(DiscoveryType("Trakt"))
        val feed = withTimeout(5_000) { board.state.first { it.feed is DiscoveryFeed.Loaded } }.feed as DiscoveryFeed.Loaded
        assertEquals(listOf("x1"), feed.items.map { it.id })
        assertEquals("mine", board.state.value.selected!!.ref.catalog.id)
    }

    @Test
    fun `a required genre catalogue opens on its first genre and has no all`() = runBlocking {
        registry = AddonRegistry(listOf(addon(cat("movie", "g", requiredGenre()))))
        server.onJson("$base/catalog/movie/g/genre=Action.json", metas("a1"))
        server.onJson("$base/catalog/movie/g/genre=Drama.json", metas("d1"))
        board.open()
        val first = withTimeout(5_000) { board.state.first { it.feed is DiscoveryFeed.Loaded } }
        assertEquals("Action", first.genre)
        assertTrue(first.selected!!.genreRequired)
        board.selectGenre(null) // refused: the addon can't answer without a genre
        delay(200)
        assertEquals("Action", board.state.value.genre)
        board.selectGenre("Drama")
        val second = withTimeout(5_000) { board.state.first { it.genre == "Drama" && it.feed is DiscoveryFeed.Loaded } }
        assertEquals(listOf("d1"), (second.feed as DiscoveryFeed.Loaded).items.map { it.id })
    }

    @Test
    fun `an optional genre still opens on all`() = runBlocking {
        registry = AddonRegistry(listOf(addon(cat("movie", "g", listOf(CatalogExtra("genre", options = genres))))))
        server.onJson("$base/catalog/movie/g.json", metas("a1"))
        board.open()
        withTimeout(5_000) { board.state.first { it.feed is DiscoveryFeed.Loaded } }
        assertEquals(null, board.state.value.genre)
        assertFalse(board.state.value.selected!!.genreRequired)
    }

    @Test
    fun `when the chosen type disappears the board falls back to movies`() = runBlocking {
        registry = AddonRegistry(listOf(addon(cat("movie", "m"), cat("Trakt", "mine"))))
        server.onJson("$base/catalog/movie/m.json", metas("m1"))
        server.onJson("$base/catalog/Trakt/mine.json", metas("x1"))
        board.open()
        board.selectType(DiscoveryType("Trakt"))
        withTimeout(5_000) { board.state.first { it.feed is DiscoveryFeed.Loaded && it.type.id == "Trakt" } }
        registry = AddonRegistry(listOf(addon(cat("movie", "m"))))
        board.onRegistryChanged()
        val after = withTimeout(5_000) { board.state.first { it.type == DiscoveryType.MOVIE && it.feed is DiscoveryFeed.Loaded } }
        assertEquals(listOf("movie", "series"), after.types.map { it.id })
        assertEquals("m", after.selected!!.ref.catalog.id)
    }
}
