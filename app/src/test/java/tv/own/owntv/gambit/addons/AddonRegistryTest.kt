package tv.own.owntv.gambit.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.TestAddons.lists
import tv.own.owntv.gambit.addons.TestAddons.meta
import tv.own.owntv.gambit.addons.TestAddons.streamsA
import tv.own.owntv.gambit.addons.TestAddons.streamsB
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.InstalledAddon
import tv.own.owntv.gambit.addons.model.AddonManifest
import tv.own.owntv.gambit.addons.model.AddonResource
import tv.own.owntv.gambit.addons.net.AddonUrl

class AddonRegistryTest {
    private val registry = AddonRegistry(listOf(meta, lists, streamsA, streamsB))

    @Test
    fun `browse catalogs come from all enabled addons in order, without search only ones`() {
        val refs = registry.browseCatalogs()
        assertEquals(
            listOf("Example Meta/popular/movie", "Example Meta/popular/series", "Example Lists/list.watchlist/movie"),
            refs.map { "${it.addon.manifest.name}/${it.catalog.id}/${it.catalog.type}" },
        )
        assertEquals(listOf("popular", "list.watchlist"), registry.browseCatalogs("movie").map { it.catalog.id })
    }

    @Test
    fun `search catalogs are those taking a search extra`() {
        assertEquals(listOf("search", "list.trending"), registry.searchCatalogs().map { it.catalog.id })
        assertEquals(listOf("list.trending"), registry.searchCatalogs("series").map { it.catalog.id })
    }

    @Test
    fun `catalog keys are unique across addons`() {
        val keys = registry.browseCatalogs().map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `disabled addons are skipped everywhere`() {
        val r = registry.withEnabled(meta.url, false)
        assertTrue(r.browseCatalogs().none { it.addon.url == meta.url })
        assertTrue(r.metaAddons("movie", "tt1").isEmpty())
        assertEquals(4, r.all.size)
        assertEquals(3, r.enabled.size)
    }

    @Test
    fun `meta addons are matched on resource, type and prefix, in order`() {
        val second = InstalledAddon(
            AddonUrl.parse("https://meta2.example/x")!!,
            AddonManifest(id = "m2", name = "Meta Two", version = "1", resources = listOf(AddonResource("meta")), types = listOf("movie")),
        )
        val r = registry.withAdded(second)
        assertEquals(listOf("Example Meta", "Meta Two"), r.metaAddons("movie", "tt1").map { it.manifest.name })
        assertEquals(listOf("Meta Two"), r.metaAddons("movie", "kitsu:1").map { it.manifest.name })
        assertTrue(r.metaAddons("tv", "tt1").isEmpty())
    }

    @Test
    fun `moving an addon to the top makes it answer meta first`() {
        val other = InstalledAddon(
            AddonUrl.parse("https://meta2.example/x")!!,
            AddonManifest(id = "m2", name = "Meta Two", version = "1", resources = listOf(AddonResource("meta")), types = listOf("movie")),
        )
        val r = registry.withAdded(other).moved(other.url, 0)
        assertEquals("Meta Two", r.metaAddons("movie", "tt1").first().manifest.name)
        assertEquals(other.url, r.all.first().url)
        assertEquals(other.url, r.moved(other.url, 99).all.last().url)
        assertEquals(r.all, r.moved(AddonUrl.parse("https://unknown.example")!!, 0).all)
    }

    @Test
    fun `stream addons match type and prefix`() {
        assertEquals(listOf(streamsA.url, streamsB.url), registry.streamAddons("series", "tt1:1:1").map { it.url })
        assertEquals(listOf(streamsA.url, streamsB.url), registry.streamAddons("movie", "kitsu:5").map { it.url })
        assertTrue(registry.streamAddons("movie", "tmdb:5").isEmpty())
        assertTrue(registry.streamAddons("anime", "tt1").isEmpty())
    }

    @Test
    fun `re-adding the same url replaces in place and removing drops it`() {
        val updated = streamsA.copy(enabled = false)
        val r = registry.withAdded(updated)
        assertEquals(4, r.all.size)
        assertEquals(2, r.all.indexOfFirst { it.url == streamsA.url })
        assertFalse(r.all[2].enabled)
        assertEquals(3, r.withRemoved(streamsA.url).all.size)
    }

    @Test
    fun `toString never shows addon urls`() {
        val text = registry.toString() + registry.browseCatalogs().joinToString()
        assertFalse(text, text.contains("KEY-"))
        assertTrue(text.contains("Example Meta (meta.example)"))
    }
}
