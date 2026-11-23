package tv.own.owntv.gambit.addons

import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.gambit.addons.FakeAddonServer.Reply
import tv.own.owntv.gambit.addons.net.AddonClient
import tv.own.owntv.gambit.addons.net.AddonError
import tv.own.owntv.gambit.addons.net.AddonResult
import tv.own.owntv.gambit.addons.net.AddonSource
import tv.own.owntv.gambit.addons.net.AddonUrl
import tv.own.owntv.gambit.addons.net.CatalogExtraArgs

/** Catalogue calls get one retry on a timeout, a network failure or 502/503/504; nothing else is retried. */
class CatalogRetryTest {
    private val base = "https://addon.example/KEY"
    private val addon = AddonUrl.parse("$base/manifest.json")!!
    private val source = AddonSource(null, "addon.example")
    private val server = FakeAddonServer()
    private val client = AddonClient(server.client)
    private val extras = CatalogExtraArgs(genre = "Action", skip = 20)
    private val url = "$base/catalog/movie/popular/genre=Action&skip=20.json"
    private val good = Reply.Body(Fixtures.read("catalog_movies.json"))

    private fun route(vararg replies: Reply) = server.on(url, Reply.Sequence(replies.toList()))

    private fun catalog() = runBlocking { client.catalog(addon, "movie", "popular", extras) }

    private fun items(result: AddonResult<*>): Int = ((result as? AddonResult.Ok)?.value as List<*>).size

    @Test
    fun `timeout then success gives the items`() {
        route(Reply.Fail(SocketTimeoutException("timeout")), good)
        assertEquals(3, items(catalog()))
        assertEquals(2, server.requested.size)
    }

    @Test
    fun `503 then success gives the items`() {
        route(Reply.Body("{}", 503), good)
        assertEquals(3, items(catalog()))
        assertEquals(2, server.requested.size)
    }

    @Test
    fun `404 is not retried`() {
        route(Reply.Body("{}", 404), good)
        assertEquals(AddonResult.Err(AddonError.HttpStatus(source, 404)), catalog())
        assertEquals(1, server.requested.size)
    }

    @Test
    fun `two timeouts fail after exactly two attempts`() {
        route(Reply.Fail(SocketTimeoutException("timeout")))
        assertEquals(AddonResult.Err(AddonError.Timeout(source)), catalog())
        assertEquals(2, server.requested.size)
    }

    @Test
    fun `the retry sends the identical url`() {
        route(Reply.Body("{}", 502), good)
        catalog()
        assertEquals(listOf(url, url), server.requested.toList())
    }

    @Test
    fun `a bad body is not retried`() {
        route(Reply.Body("not json"), good)
        catalog()
        assertEquals(1, server.requested.size)
    }

    @Test
    fun `other calls are not retried`() = runBlocking {
        server.on("$base/meta/movie/tt1.json", Reply.Sequence(listOf(Reply.Body("{}", 503), good)))
        client.meta(addon, "movie", "tt1")
        assertEquals(1, server.requested.size)
    }
}
