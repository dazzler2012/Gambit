package tv.own.owntv.gambit.addons

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tv.own.owntv.gambit.addons.FakeAddonServer.Reply
import tv.own.owntv.gambit.addons.net.AddonClient
import tv.own.owntv.gambit.addons.net.AddonError
import tv.own.owntv.gambit.addons.net.AddonResult
import tv.own.owntv.gambit.addons.net.AddonUrl
import tv.own.owntv.gambit.addons.net.CatalogExtraArgs

class AddonClientTest {
    private val secretBase = "https://addon.example/SECRETKEY123"
    private val addon = AddonUrl.parse("$secretBase/manifest.json")!!
    private val server = FakeAddonServer()
    private val client = AddonClient(server.client)

    private fun <T> ok(result: AddonResult<T>): T = (result as? AddonResult.Ok)?.value
        ?: throw AssertionError("expected Ok, got ${result.errorOrNull()}")

    @Test
    fun `manifest is fetched and parsed`() = runBlocking {
        server.onFixture("$secretBase/manifest.json", "manifest_meta.json")
        assertEquals("org.example.meta", ok(client.manifest(addon)).id)
    }

    @Test
    fun `catalog request carries extras and keeps the query string`() = runBlocking {
        val withQuery = AddonUrl.parse("https://addon.example/abc/manifest.json?cfg=1")!!
        server.onFixture("https://addon.example/abc/catalog/movie/popular/genre=Action&skip=100.json?cfg=1", "catalog_movies.json")
        val metas = ok(client.catalog(withQuery, "movie", "popular", CatalogExtraArgs(genre = "Action", skip = 100)))
        assertEquals(3, metas.size)
    }

    @Test
    fun `search catalog request`() = runBlocking {
        server.onFixture("$secretBase/catalog/movie/search/search=star%20wars.json", "catalog_empty.json")
        assertTrue(ok(client.catalog(addon, "movie", "search", CatalogExtraArgs(search = "star wars"))).isEmpty())
    }

    @Test
    fun `meta and episode streams use the standard paths`() = runBlocking {
        server.onFixture("$secretBase/meta/series/tt0000100.json", "meta_series.json")
        server.onFixture("$secretBase/stream/series/tt0000100%3A1%3A1.json", "streams_mixed.json")
        assertEquals(3, ok(client.meta(addon, "series", "tt0000100")).videos.size)
        assertEquals(2, ok(client.streams(addon, "series", "tt0000100:1:1")).count { it.isPlayable })
    }

    @Test
    fun `non 2xx is a typed http error`() = runBlocking {
        server.onJson("$secretBase/manifest.json", "{}", code = 503)
        assertEquals(AddonResult.Err(AddonError.HttpStatus("addon.example", 503)), client.manifest(addon))
        assertEquals(AddonResult.Err(AddonError.HttpStatus("addon.example", 404)), client.meta(addon, "movie", "tt9"))
    }

    @Test
    fun `malformed body is a typed error`() = runBlocking {
        server.onFixture("$secretBase/stream/movie/tt1.json", "malformed.json")
        assertEquals(AddonResult.Err(AddonError.MalformedJson("label")), client.streams(addon, "movie", "tt1", label = "label"))
    }

    @Test
    fun `missing field is a typed error`() = runBlocking {
        server.onJson("$secretBase/catalog/movie/popular.json", """{"items":[]}""")
        assertEquals(AddonResult.Err(AddonError.MissingField("addon.example", "metas")), client.catalog(addon, "movie", "popular"))
    }

    @Test
    fun `socket timeout is a typed timeout`() = runBlocking {
        server.on("$secretBase/manifest.json", Reply.Fail(SocketTimeoutException("timeout")))
        assertEquals(AddonResult.Err(AddonError.Timeout("addon.example")), client.manifest(addon))
    }

    @Test
    fun `whole call time limit is a typed timeout`() = runBlocking {
        val slowClient = AddonClient(server.client, timeoutMs = 100)
        server.on("$secretBase/manifest.json", Reply.Slow(1_000, Fixtures.read("manifest_meta.json")))
        assertEquals(AddonResult.Err(AddonError.Timeout("addon.example")), slowClient.manifest(addon))
    }

    @Test
    fun `connection failure keeps the exception type only`() = runBlocking {
        server.on("$secretBase/manifest.json", Reply.Fail(UnknownHostException("addon.example")))
        assertEquals(AddonResult.Err(AddonError.Network("addon.example", "UnknownHostException")), client.manifest(addon))
    }

    @Test
    fun `errors never carry the url even when the exception message does`() = runBlocking {
        server.on("$secretBase/manifest.json", Reply.Fail(IOException("unexpected end of stream on $secretBase/manifest.json")))
        server.onJson("$secretBase/meta/movie/tt1.json", "{}", code = 500)
        val errors = listOf(client.manifest(addon), client.meta(addon, "movie", "tt1")).map { it.errorOrNull()!! }
        errors.forEach { assertFalse(it.toString(), it.toString().contains("SECRETKEY123")) }
    }

    @Test
    fun `cancellation is thrown, not turned into an error`() {
        server.on("$secretBase/manifest.json", Reply.Slow(2_000, "{}"))
        try {
            runBlocking { withTimeout(100) { client.manifest(addon) } }
            fail("expected cancellation")
        } catch (_: TimeoutCancellationException) {
        }
    }
}
