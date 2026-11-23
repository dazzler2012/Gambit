package tv.own.owntv.gambit.stremio

import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.net.AddonUrl

class StremioClientTest {
    private val host = FakeStremioHost()
    private val client = FakeStremio.client(host)
    private val linkCode = StremioLinkCode("AB12", "https://link.stremio.example/AB12")

    // --- link code ---

    @Test
    fun `create link code sends the stremio-core request and reads code and link`() = runBlocking {
        host.onJson(
            "GET", "/api/v2/create",
            """{"result":{"success":true,"code":"AB12","link":"https://link.stremio.example/AB12","qrcode":"https://link.stremio.example/qr/AB12"}}""",
        )
        val result = client.createLinkCode()
        assertEquals(StremioResult.Ok(linkCode), result)
        val request = host.seen.single()
        assertEquals("GET", request.method)
        assertEquals("https://link.stremio.example/api/v2/create?type=Create", request.url)
    }

    @Test
    fun `create link code without a code is malformed`() = runBlocking {
        host.onJson("GET", "/api/v2/create", """{"result":{"success":true,"link":"https://link.stremio.example/x"}}""")
        assertEquals(StremioResult.Err(StremioError.Malformed("missing code")), client.createLinkCode())
    }

    @Test
    fun `read link code sends the code as a query value`() = runBlocking {
        host.onJson("GET", "/api/v2/read", WAITING)
        client.readLinkCode(linkCode)
        assertEquals("https://link.stremio.example/api/v2/read?type=Read&code=AB12", host.seen.single().url)
    }

    @Test
    fun `error 101 means waiting`() = runBlocking {
        host.onJson("GET", "/api/v2/read", WAITING)
        assertEquals(StremioResult.Ok(StremioLinkStatus.Waiting), client.readLinkCode(linkCode))
    }

    @Test
    fun `other link errors are typed with their code`() = runBlocking {
        host.onJson("GET", "/api/v2/read", """{"error":{"code":7,"message":"something"}}""")
        assertEquals(StremioResult.Err(StremioError.Api(7)), client.readLinkCode(linkCode))
    }

    @Test
    fun `approved code yields the key, which prints hidden`() = runBlocking {
        host.onJson("GET", "/api/v2/read", LINKED)
        val status = (client.readLinkCode(linkCode) as StremioResult.Ok).value
        assertTrue(status is StremioLinkStatus.Linked)
        val key = (status as StremioLinkStatus.Linked).key
        assertEquals(FakeStremio.KEY, key.value)
        assertFalse(key.toString().contains(FakeStremio.KEY))
        assertFalse(status.toString().contains(FakeStremio.KEY))
    }

    @Test
    fun `a blank key is malformed, not linked`() = runBlocking {
        host.onJson("GET", "/api/v2/read", """{"result":{"authKey":"  "}}""")
        assertEquals(StremioResult.Err(StremioError.Malformed("missing authKey")), client.readLinkCode(linkCode))
    }

    @Test
    fun `non-JSON reply is malformed`() = runBlocking {
        host.onJson("GET", "/api/v2/read", "<html>busy</html>")
        assertEquals(StremioResult.Err(StremioError.Malformed("invalid JSON")), client.readLinkCode(linkCode))
    }

    // --- addon collection ---

    @Test
    fun `collection call posts the key in the body only`() = runBlocking {
        host.onJson("POST", "/api/addonCollectionGet", COLLECTION)
        client.addonCollection(StremioAuthKey(FakeStremio.KEY))

        val request = host.seen.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.stremio.example/api/addonCollectionGet", request.url)
        val body = JSONObject(request.body)
        assertEquals("AddonCollectionGet", body.getString("type"))
        assertEquals(FakeStremio.KEY, body.getString("authKey"))
        assertTrue(body.getBoolean("update"))
        assertFalse(request.url.contains(FakeStremio.KEY))
        assertFalse(request.headers.toString().contains(FakeStremio.KEY))
    }

    @Test
    fun `the key is sent in exactly one request across a whole link and import`() = runBlocking {
        host.onJson("GET", "/api/v2/create", """{"result":{"code":"AB12","link":"https://link.stremio.example/AB12"}}""")
        host.onJson("GET", "/api/v2/read", LINKED)
        host.onJson("POST", "/api/addonCollectionGet", COLLECTION)

        val code = (client.createLinkCode() as StremioResult.Ok).value
        val key = ((client.readLinkCode(code) as StremioResult.Ok).value as StremioLinkStatus.Linked).key
        client.addonCollection(key)

        val carrying = host.seen.filter { it.url.contains(FakeStremio.KEY) || it.headers.toString().contains(FakeStremio.KEY) || it.body.contains(FakeStremio.KEY) }
        assertEquals(listOf("https://api.stremio.example/api/addonCollectionGet"), carrying.map { it.url })
        assertTrue(carrying.single().body.contains(FakeStremio.KEY))
        assertTrue(host.seen.none { it.url.contains(FakeStremio.KEY) })
    }

    @Test
    fun `collection keeps Stremio order, names and flags, and counts unreadable addresses`() = runBlocking {
        host.onJson("POST", "/api/addonCollectionGet", COLLECTION)
        val collection = (client.addonCollection(StremioAuthKey(FakeStremio.KEY)) as StremioResult.Ok).value

        assertEquals(listOf("Meta One", "Streams Two", "Cinemeta", null), collection.addons.map { it.name })
        assertEquals(AddonUrl.parse("https://meta-one.example/abc/manifest.json"), collection.addons[0].url)
        assertEquals(listOf(false, false, true, false), collection.addons.map { it.official })
        assertEquals(listOf(false, false, true, false), collection.addons.map { it.protected })
        assertEquals(2, collection.unreadable)
    }

    @Test
    fun `collection error reply is typed`() = runBlocking {
        host.onJson("POST", "/api/addonCollectionGet", """{"error":{"code":1,"message":"session does not exist"}}""")
        assertEquals(StremioResult.Err(StremioError.Api(1)), client.addonCollection(StremioAuthKey(FakeStremio.KEY)))
    }

    @Test
    fun `collection without an addons list is malformed`() = runBlocking {
        host.onJson("POST", "/api/addonCollectionGet", """{"result":{"lastModified":"2026-01-01T00:00:00.000Z"}}""")
        assertEquals(StremioResult.Err(StremioError.Malformed("missing addons")), client.addonCollection(StremioAuthKey(FakeStremio.KEY)))
    }

    // --- failures never carry the key ---

    @Test
    fun `every failure of the keyed call is free of the key`() = runBlocking {
        val replies = listOf(
            FakeStremioHost.Reply(failure = IOException("refused while sending ${FakeStremio.KEY}")),
            FakeStremioHost.Reply(failure = InterruptedIOException("timeout ${FakeStremio.KEY}")),
            FakeStremioHost.Reply(code = 500, body = """{"echo":"${FakeStremio.KEY}"}"""),
            FakeStremioHost.Reply(body = "not json ${FakeStremio.KEY}"),
            FakeStremioHost.Reply(body = """{"error":{"code":2,"message":"bad key ${FakeStremio.KEY}"}}"""),
            FakeStremioHost.Reply(body = """{"result":{"addons":"${FakeStremio.KEY}"}}"""),
        )
        val errors = replies.map { reply ->
            val fresh = FakeStremioHost().on("POST", "/api/addonCollectionGet", reply)
            val result = FakeStremio.client(fresh).addonCollection(StremioAuthKey(FakeStremio.KEY))
            (result as StremioResult.Err).error
        }
        assertEquals(
            listOf(
                StremioError.Network("IOException"),
                StremioError.Timeout,
                StremioError.HttpStatus(500),
                StremioError.Malformed("invalid JSON"),
                StremioError.Api(2),
                StremioError.Malformed("missing addons"),
            ),
            errors,
        )
        errors.forEach { assertFalse(it.toString(), it.toString().contains(FakeStremio.KEY)) }
    }

    @Test
    fun `account addons print name and host only`() {
        val addon = StremioAccountAddon("Streams Two", AddonUrl.parse("https://streams-two.example/secret-debrid-key/manifest.json")!!, false, false)
        assertFalse(addon.toString().contains("secret-debrid-key"))
        assertTrue(addon.toString().contains("streams-two.example"))
    }

    private companion object {
        const val WAITING = """{"error":{"code":101,"message":"Invalid or expired token"}}"""
        val LINKED = """{"result":{"authKey":"${FakeStremio.KEY}"}}"""
        const val COLLECTION = """
            {"result":{"lastModified":"2026-01-01T00:00:00.000Z","addons":[
              {"transportUrl":"https://meta-one.example/abc/manifest.json","manifest":{"id":"one","name":"Meta One"}},
              {"transportUrl":"https://streams-two.example/manifest.json","manifest":{"id":"two","name":"Streams Two"},"flags":{}},
              {"transportUrl":"not a url","manifest":{"id":"bad","name":"Broken"}},
              {"transportUrl":"https://cinemeta.example/manifest.json","manifest":{"id":"cm","name":"Cinemeta"},"flags":{"official":true,"protected":true}},
              {"manifest":{"id":"nourl","name":"No Address"}},
              {"transportUrl":"https://nameless.example/manifest.json","manifest":{"id":"nameless"}}
            ]}}
        """
    }
}
