package tv.own.owntv.gambit.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.model.AddonManifest
import tv.own.owntv.gambit.addons.model.AddonResource
import tv.own.owntv.gambit.addons.model.AddonResources
import tv.own.owntv.gambit.addons.net.AddonError
import tv.own.owntv.gambit.addons.net.AddonJson
import tv.own.owntv.gambit.addons.net.AddonResult

class AddonManifestTest {
    private fun manifest(name: String): AddonManifest =
        (AddonJson.manifest(Fixtures.read(name), "test") as AddonResult.Ok).value

    @Test
    fun `full manifest is parsed`() {
        val m = manifest("manifest_meta.json")
        assertEquals("org.example.meta", m.id)
        assertEquals("Example Meta", m.name)
        assertEquals("1.4.2", m.version)
        assertEquals("https://addon.example/logo.png", m.logo)
        assertEquals(listOf("movie", "series"), m.types)
        assertEquals(
            listOf(AddonResource("catalog"), AddonResource("meta", listOf("movie", "series"), listOf("tt", "tmdb:"))),
            m.resources,
        )
        assertEquals(3, m.catalogs.size)
        val popular = m.catalogs[0]
        assertEquals("movie", popular.type)
        assertEquals("popular", popular.id)
        assertEquals("Popular Movies", popular.name)
        assertEquals(listOf("Action", "Drama"), popular.extra.first { it.name == "genre" }.options)
        assertTrue(popular.supports("skip"))
        assertFalse(popular.supports("search"))
        assertEquals(listOf("search"), m.catalogs[2].requiredExtras)
        assertTrue(m.behaviorHints.configurable)
        assertFalse(m.behaviorHints.adult)
        assertNull(m.idPrefixes)
    }

    @Test
    fun `legacy extraSupported and extraRequired are read and catalogs without id dropped`() {
        val m = manifest("manifest_lists.json")
        assertEquals(2, m.catalogs.size)
        assertEquals(listOf("skip", "genre"), m.catalogs[0].extra.map { it.name })
        assertTrue(m.catalogs[0].requiredExtras.isEmpty())
        assertEquals(listOf("search"), m.catalogs[1].requiredExtras)
    }

    @Test
    fun `odd field shapes do not fail the parse`() {
        val m = manifest("manifest_odd.json")
        assertEquals("", m.version)
        assertEquals(listOf("meta", "stream"), m.resources.map { it.name })
        assertEquals(listOf("movie"), m.types)
        assertTrue(m.catalogs.isEmpty())
        assertNull(m.idPrefixes)
    }

    @Test
    fun `missing name is a typed error`() {
        assertEquals(
            AddonResult.Err(AddonError.MissingField("test", "name")),
            AddonJson.manifest(Fixtures.read("manifest_no_name.json"), "test"),
        )
    }

    @Test
    fun `malformed or non object json is a typed error`() {
        val expected = AddonResult.Err(AddonError.MalformedJson("test"))
        assertEquals(expected, AddonJson.manifest(Fixtures.read("malformed.json"), "test"))
        assertEquals(expected, AddonJson.manifest("", "test"))
        assertEquals(expected, AddonJson.manifest("[1,2]", "test"))
        assertEquals(expected, AddonJson.manifest("<html>502 Bad Gateway</html>", "test"))
    }

    @Test
    fun `serves checks resource, type and resource level id prefixes`() {
        val m = manifest("manifest_meta.json")
        assertTrue(m.serves(AddonResources.META, "movie", "tt0000001"))
        assertTrue(m.serves(AddonResources.META, "series", "tmdb:123"))
        assertFalse(m.serves(AddonResources.META, "movie", "kitsu:1"))
        assertFalse(m.serves(AddonResources.META, "channel", "tt0000001"))
        assertFalse(m.serves(AddonResources.STREAM, "movie", "tt0000001"))
    }

    @Test
    fun `addon level prefixes apply when the resource has none, and none at all accepts any id`() {
        val withAddonPrefixes = AddonManifest(
            id = "a", name = "A", version = "1",
            resources = listOf(AddonResource("meta")), types = listOf("movie"), idPrefixes = listOf("tt"),
        )
        assertTrue(withAddonPrefixes.serves("meta", "movie", "tt1"))
        assertFalse(withAddonPrefixes.serves("meta", "movie", "kitsu:1"))

        val noPrefixes = withAddonPrefixes.copy(idPrefixes = null)
        assertTrue(noPrefixes.serves("meta", "movie", "anything:1"))
    }

    @Test
    fun `resource level types override addon level types`() {
        val m = AddonManifest(
            id = "a", name = "A", version = "1",
            resources = listOf(AddonResource("stream", types = listOf("movie"))), types = listOf("movie", "series"),
        )
        assertTrue(m.serves("stream", "movie", "tt1"))
        assertFalse(m.serves("stream", "series", "tt1:1:1"))
    }
}
