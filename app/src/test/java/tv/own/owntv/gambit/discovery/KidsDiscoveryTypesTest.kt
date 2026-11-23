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

/** Kids rules for the all-types Discovery: approved catalogues only, and never one with a required extra. */
class KidsDiscoveryTypesTest {
    private val server = FakeAddonServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository = AddonRepository(AddonClient(server.client))
    private val gate = KidsTitleGate()
    private val base = "https://kids-types.example/KEY-K"

    private val genres = listOf("Action", "Drama")
    private fun cat(type: String, id: String, extra: List<CatalogExtra> = listOf(CatalogExtra("skip"))) = AddonCatalog(type, id, "Cat $id", extra)

    private val all = AddonRegistry(listOf(
        InstalledAddon(
            AddonUrl.parse("$base/manifest.json")!!,
            AddonManifest(
                "k", "Addon k", "1.0.0", resources = listOf(AddonResource("catalog")),
                catalogs = listOf(
                    cat("movie", "films"),
                    cat("Trakt", "mine"),
                    cat("Trakt", "other"),
                    cat("collection", "boxsets"),
                    cat("movie", "bygenre", listOf(CatalogExtra("genre", isRequired = true, options = genres))),
                    cat("Trakt", "trakt.bygenre", listOf(CatalogExtra("genre", isRequired = true, options = genres))),
                ),
            ),
            setupId = tv.own.owntv.gambit.addons.TestAddons.setupId("k"),
        ),
    ))

    private fun key(id: String) = all.discoverCatalogs().first { it.catalog.id == id }.key

    private var approved: Set<String> = emptySet()
    private fun approve(vararg ids: String) {
        approved = ids.map(::key).toSet()
        gate.retainApproved(1, approved)
    }

    private fun board() = DiscoveryBoard(repository, { all.limitedTo(approved) }, scope, gate, { true })

    @After
    fun tearDown() = scope.cancel()

    private fun metas(vararg ids: String) =
        """{"metas":[${ids.joinToString(",") { """{"id":"$it","type":"movie","name":"Title $it"}""" }}]}"""

    @Test
    fun `kids tabs list only types with an approved catalogue`() = runBlocking {
        approve("mine")
        val b = board().also { it.open() }
        assertEquals(listOf("movie", "series", "Trakt"), b.state.value.types.map { it.id })
        assertEquals(listOf("movie", "series", "Trakt"), all.limitedTo(approved).discoverTypes())
    }

    @Test
    fun `an unapproved type never appears for kids`() = runBlocking {
        approve("films")
        val b = board().also { it.open() }
        assertEquals(listOf("movie", "series"), b.state.value.types.map { it.id })
        for (hidden in listOf("Trakt", "collection")) {
            b.selectType(DiscoveryType(hidden)) // not offered: the board falls back to movies
            assertEquals(DiscoveryType.MOVIE, b.state.value.type)
            assertTrue(b.state.value.types.none { it.id == "Trakt" || it.id == "collection" })
            assertEquals(listOf("films"), b.state.value.catalogs.map { it.ref.catalog.id })
        }
    }

    @Test
    fun `kids see only the approved catalogues of an approved type`() = runBlocking {
        approve("mine")
        val b = board().also { it.open() }
        b.selectType(DiscoveryType("Trakt"))
        assertEquals(listOf("mine"), b.state.value.catalogs.map { it.ref.catalog.id })
    }

    @Test
    fun `a required genre catalogue stays hidden for kids even when approved`() = runBlocking {
        // The keys exist (the parent's page could not list them, but a stale or hand-made key might).
        approved = setOf(
            all.discoverCatalogs().first { it.catalog.id == "bygenre" }.key,
            all.discoverCatalogs().first { it.catalog.id == "trakt.bygenre" }.key,
            key("films"),
        )
        gate.retainApproved(1, approved)
        val limited = all.limitedTo(approved)
        assertEquals(listOf("films"), limited.discoverCatalogs().map { it.catalog.id })
        assertEquals(listOf("movie", "series"), limited.discoverTypes())
        val b = board().also { it.open() }
        assertEquals(listOf("films"), b.state.value.catalogs.map { it.ref.catalog.id })
        b.selectType(DiscoveryType("Trakt")) // not offered: the board falls back to movies
        assertEquals(DiscoveryType.MOVIE, b.state.value.type)
        assertTrue(b.state.value.types.none { it.id == "Trakt" || it.id == "collection" })
        assertEquals(listOf("films"), b.state.value.catalogs.map { it.ref.catalog.id })
    }

    @Test
    fun `adults see the required genre catalogues the kids registry hides`() {
        assertTrue(all.discoverCatalogs().any { it.catalog.id == "bygenre" })
        assertFalse(all.limitedTo(approved).discoverCatalogs().any { it.catalog.id == "bygenre" })
    }

    @Test
    fun `the title gate refuses ids from a request with a genre`() = runBlocking {
        approve("films")
        gate.report(key("films"), listOf("t1"), CatalogExtraArgs(genre = "Action"))
        assertFalse(gate.allows(key("films"), "t1"))
        gate.report(key("films"), listOf("t2"), CatalogExtraArgs(skip = 20))
        assertTrue(gate.allows(key("films"), "t2"))
    }

    @Test
    fun `a kids board loads a custom type catalogue and reports its titles to the gate`() = runBlocking {
        approve("mine")
        server.onJson("$base/catalog/Trakt/mine.json", metas("x1"))
        val b = board().also { it.open() }
        b.selectType(DiscoveryType("Trakt"))
        withTimeout(5_000) { b.state.first { it.feed is DiscoveryFeed.Loaded } }
        delay(50)
        assertTrue(gate.allows(key("mine"), "x1"))
        assertFalse(gate.allows(key("mine"), "nope"))
    }
}
