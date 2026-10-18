package tv.own.owntv.gambit.addons

import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.FakeAddonServer.Reply
import tv.own.owntv.gambit.addons.TestAddons.LISTS_BASE
import tv.own.owntv.gambit.addons.TestAddons.META_BASE
import tv.own.owntv.gambit.addons.TestAddons.STREAMS_A_BASE
import tv.own.owntv.gambit.addons.TestAddons.STREAMS_B_BASE
import tv.own.owntv.gambit.addons.TestAddons.lists
import tv.own.owntv.gambit.addons.TestAddons.meta
import tv.own.owntv.gambit.addons.TestAddons.streamsA
import tv.own.owntv.gambit.addons.TestAddons.streamsB
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.data.InstalledAddon
import tv.own.owntv.gambit.addons.model.AddonManifest
import tv.own.owntv.gambit.addons.model.AddonResource
import tv.own.owntv.gambit.addons.model.StreamSupport
import tv.own.owntv.gambit.addons.net.AddonClient
import tv.own.owntv.gambit.addons.net.AddonError
import tv.own.owntv.gambit.addons.net.AddonResult
import tv.own.owntv.gambit.addons.net.AddonUrl
import tv.own.owntv.gambit.addons.net.CatalogExtraArgs

class AddonRepositoryTest {
    private val server = FakeAddonServer()
    private val repo = AddonRepository(AddonClient(server.client))
    private val registry = AddonRegistry(listOf(meta, lists, streamsA, streamsB))

    @Test
    fun `install normalizes the url and fetches the manifest`() = runBlocking {
        server.onFixture("$META_BASE/manifest.json", "manifest_meta.json")
        val installed = (repo.install("stremio://meta.example/KEY-META/manifest.json") as AddonResult.Ok).value
        assertEquals("org.example.meta", installed.manifest.id)
        assertEquals(AddonUrl.parse("$META_BASE/manifest.json"), installed.url)
        assertTrue(installed.enabled)
    }

    @Test
    fun `install rejects bad input without a request`() = runBlocking {
        assertEquals(AddonResult.Err(AddonError.InvalidUrl), repo.install("not a url"))
        assertTrue(server.requested.isEmpty())
    }

    @Test
    fun `install reports http failure with a redacted label`() = runBlocking {
        server.onJson("$META_BASE/manifest.json", "{}", code = 401)
        assertEquals(AddonResult.Err(AddonError.HttpStatus("meta.example", 401)), repo.install("$META_BASE/manifest.json"))
    }

    @Test
    fun `refresh keeps url and enabled state`() = runBlocking {
        server.onFixture("$STREAMS_A_BASE/manifest.json", "manifest_meta.json")
        val off = streamsA.copy(enabled = false)
        val refreshed = (repo.refresh(off) as AddonResult.Ok).value
        assertEquals(off.url, refreshed.url)
        assertFalse(refreshed.enabled)
        assertEquals("org.example.meta", refreshed.manifest.id)
    }

    @Test
    fun `catalog goes to the addon that owns it`() = runBlocking {
        server.onFixture("$LISTS_BASE/catalog/movie/list.watchlist/skip=100.json", "catalog_movies.json")
        val ref = registry.browseCatalogs().first { it.catalog.id == "list.watchlist" }
        val metas = (repo.catalog(ref, CatalogExtraArgs(skip = 100)) as AddonResult.Ok).value
        assertEquals(3, metas.size)
        assertEquals(listOf("$LISTS_BASE/catalog/movie/list.watchlist/skip=100.json"), server.requested)
    }

    @Test
    fun `catalog error carries the addon label, not the url`() = runBlocking {
        val ref = registry.browseCatalogs().first()
        assertEquals(AddonResult.Err(AddonError.HttpStatus("Example Meta (meta.example)", 404)), repo.catalog(ref))
    }

    @Test
    fun `meta comes from the first matching addon`() = runBlocking {
        val second = InstalledAddon(
            AddonUrl.parse("https://meta2.example/x")!!,
            AddonManifest(id = "m2", name = "Meta Two", version = "1", resources = listOf(AddonResource("meta")), types = listOf("series")),
        )
        server.onFixture("$META_BASE/meta/series/tt0000100.json", "meta_series.json")
        server.onJson("https://meta2.example/x/meta/series/tt0000100.json", """{"meta":{"id":"tt0000100","name":"Wrong"}}""")
        val meta = (repo.meta(registry.withAdded(second), "series", "tt0000100") as AddonResult.Ok).value
        assertEquals("Example Show", meta.name)
        assertEquals(listOf("$META_BASE/meta/series/tt0000100.json"), server.requested)
    }

    @Test
    fun `meta falls through to the next addon when the first fails`() = runBlocking {
        val second = InstalledAddon(
            AddonUrl.parse("https://meta2.example/x")!!,
            AddonManifest(id = "m2", name = "Meta Two", version = "1", resources = listOf(AddonResource("meta")), types = listOf("series")),
        )
        server.on("$META_BASE/meta/series/tt0000100.json", Reply.Fail(SocketTimeoutException()))
        server.onFixture("https://meta2.example/x/meta/series/tt0000100.json", "meta_series.json")
        val meta = (repo.meta(registry.withAdded(second), "series", "tt0000100") as AddonResult.Ok).value
        assertEquals("tt0000100", meta.id)
    }

    @Test
    fun `meta returns the first error when every addon fails`() = runBlocking {
        server.onFixture("$META_BASE/meta/movie/tt1.json", "meta_null.json")
        assertEquals(AddonResult.Err(AddonError.NotFound("Example Meta (meta.example)")), repo.meta(registry, "movie", "tt1"))
    }

    @Test
    fun `meta with no matching addon is a typed error and makes no request`() = runBlocking {
        assertEquals(AddonResult.Err(AddonError.NoAddon("meta", "movie")), repo.meta(registry, "movie", "kitsu:1"))
        assertTrue(server.requested.isEmpty())
    }

    @Test
    fun `streams are gathered from every stream addon and split by support`() = runBlocking {
        server.onFixture("$STREAMS_A_BASE/stream/movie/tt0000001.json", "streams_mixed.json")
        server.onFixture("$STREAMS_B_BASE/stream/movie/tt0000001.json", "streams_torrent_only.json")
        val results = repo.streams(registry, "movie", "tt0000001")
        assertEquals(2, results.playable.size)
        assertTrue(results.playable.all { it.stream.support == StreamSupport.PLAYABLE })
        assertEquals(7, results.unsupported.size)
        assertEquals(3, results.unsupported.count { it.stream.support == StreamSupport.TORRENT })
        assertTrue(results.unsupported.all { it.addonName == "Example Streams" })
        assertTrue(results.errors.isEmpty())
        assertEquals(setOf("$STREAMS_A_BASE/stream/movie/tt0000001.json", "$STREAMS_B_BASE/stream/movie/tt0000001.json"), server.requested.toSet())
    }

    @Test
    fun `torrent only results give nothing playable and no crash`() = runBlocking {
        server.onFixture("$STREAMS_A_BASE/stream/movie/tt2.json", "streams_torrent_only.json")
        server.onFixture("$STREAMS_B_BASE/stream/movie/tt2.json", "streams_torrent_only.json")
        val results = repo.streams(registry, "movie", "tt2")
        assertTrue(results.playable.isEmpty())
        assertEquals(4, results.unsupported.size)
    }

    @Test
    fun `one failing stream addon does not hide the other`() = runBlocking {
        server.onFixture("$STREAMS_A_BASE/stream/series/tt0000100%3A1%3A1.json", "streams_mixed.json")
        server.onFixture("$STREAMS_B_BASE/stream/series/tt0000100%3A1%3A1.json", "malformed.json")
        val results = repo.streams(registry, "series", "tt0000100:1:1")
        assertEquals(2, results.playable.size)
        assertEquals(listOf<AddonError>(AddonError.MalformedJson("Example Streams (streams-b.example)")), results.errors)
    }

    @Test
    fun `stream results keep addon order`() = runBlocking {
        server.on("$STREAMS_A_BASE/stream/movie/tt3.json", Reply.Slow(200, Fixtures.read("streams_mixed.json")))
        server.onJson("$STREAMS_B_BASE/stream/movie/tt3.json", """{"streams":[{"name":"B","url":"https://b.example/v.mkv"}]}""")
        val results = repo.streams(registry.moved(streamsB.url, 0), "movie", "tt3")
        assertEquals("B", results.playable.first().stream.name)
        assertEquals(3, results.playable.size)
    }

    @Test
    fun `streams with no matching addon is a typed error`() = runBlocking {
        val results = repo.streams(registry, "channel", "tt1")
        assertEquals(listOf<AddonError>(AddonError.NoAddon("stream", "channel")), results.errors)
        assertTrue(server.requested.isEmpty())
    }
}
