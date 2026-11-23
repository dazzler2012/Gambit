package tv.own.owntv.gambit.stremio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.FakeAddonServer
import tv.own.owntv.gambit.addons.data.AddonAddResult
import tv.own.owntv.gambit.addons.data.AddonImportFailReason
import tv.own.owntv.gambit.addons.data.AddonImportFailure
import tv.own.owntv.gambit.addons.data.AddonImportReport
import tv.own.owntv.gambit.addons.data.AddonImportRequest
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.data.AddonStore
import tv.own.owntv.gambit.addons.data.AddonVault
import tv.own.owntv.gambit.addons.net.AddonClient
import tv.own.owntv.gambit.addons.net.AddonUrl

/**
 * Configured addons such as Torrentio keep their settings in the path, with | ; = and , in it. Stremio
 * may hand the address back with those escaped (%7C) or not. This pins down what goes on the wire for
 * each form, for both ways in: the import and Type an address. Fake hosts only.
 */
class TorrentioStyleAddressTest {
    private class MemoryVault : AddonVault {
        var text: String? = null
        override fun hasSaved(): Boolean = text != null
        override fun read(): String? = text
        override fun write(text: String): Boolean { this.text = text; return true }
    }

    private val server = FakeAddonServer()
        .onFixture(CANONICAL, "manifest_streams.json")
        .onFixture(CANONICAL_WITH_QUERY, "manifest_streams.json")

    private fun store() = AddonStore(AddonRepository(AddonClient(server.client)), MemoryVault())

    private fun request(address: String, name: String? = null) = listOf(AddonImportRequest(AddonUrl.parse(address)!!, name))

    @Test
    fun `raw and escaped pipes have the same canonical form, double escaping does not`() {
        val raw = AddonUrl.parse(RAW)!!
        val escaped = AddonUrl.parse(ESCAPED)!!
        val double = AddonUrl.parse(DOUBLE_ESCAPED)!!
        assertEquals(CANONICAL, raw.canonical)
        assertEquals(CANONICAL, escaped.canonical)
        assertTrue(raw.sameAddonAs(escaped))
        assertFalse(raw.sameAddonAs(double))
        assertFalse(raw.sameAddonAs(AddonUrl.parse(RAW.replace("sort=qualitysize", "sort=size"))!!))
        assertEquals(CANONICAL_WITH_QUERY, AddonUrl.parse(RAW_WITH_QUERY)!!.canonical)
    }

    @Test
    fun `Gambit keeps the address exactly as given, escaped or not`() {
        assertEquals(RAW, AddonUrl.parse(RAW)!!.manifestUrl)
        assertEquals(ESCAPED, AddonUrl.parse(ESCAPED)!!.manifestUrl)
        assertEquals(DOUBLE_ESCAPED, AddonUrl.parse(DOUBLE_ESCAPED)!!.manifestUrl)
        assertEquals(RAW_WITH_QUERY, AddonUrl.parse(RAW_WITH_QUERY)!!.manifestUrl)
    }

    @Test
    fun `raw and escaped pipes reach the addon as the same request`() = runBlocking {
        listOf(RAW, ESCAPED).forEach { address ->
            server.requested.clear()
            val report = store().importAll(request(address), kidsApproved = emptySet())
            assertEquals(address, AddonImportReport(added = 1), report)
            assertEquals(listOf(CANONICAL), server.requested.toList())
        }
    }

    @Test
    fun `a query on a configured address survives`() = runBlocking {
        val report = store().importAll(request(RAW_WITH_QUERY), kidsApproved = emptySet())
        assertEquals(AddonImportReport(added = 1), report)
        assertEquals(listOf(CANONICAL_WITH_QUERY), server.requested.toList())
    }

    @Test
    fun `a double-escaped address is sent double-escaped, not repaired`() = runBlocking {
        val report = store().importAll(request(DOUBLE_ESCAPED, "Torrent"), kidsApproved = emptySet())
        assertEquals(listOf(AddonImportFailure("Torrent", AddonImportFailReason.Refused(404))), report.failures)
        assertTrue(server.requested.single().contains("%257C"))
    }

    @Test
    fun `typing the address and importing it send the same request`() = runBlocking {
        listOf(RAW, ESCAPED).forEach { address ->
            server.requested.clear()
            val typed = store().add(address)
            assertTrue(address, typed is AddonAddResult.Added)
            val viaTyping = server.requested.toList()

            server.requested.clear()
            store().importAll(request(address), kidsApproved = emptySet())
            assertEquals(viaTyping, server.requested.toList())
        }
    }

    @Test
    fun `the Stremio list reader accepts both forms`() {
        val body = """{"result":{"addons":[
            {"transportUrl":"$RAW","manifest":{"name":"Raw"}},
            {"transportUrl":"$ESCAPED","manifest":{"name":"Escaped"}}
        ]}}"""
        val collection = (StremioJson.addonCollection(body) as StremioResult.Ok).value
        assertEquals(0, collection.unreadable)
        assertEquals(listOf(false, false), collection.addons.map { it.legacy })
        collection.addons.forEach { assertNotNull(it.url) }
    }

    private companion object {
        const val CONFIG = "providers=yts,eztv|sort=qualitysize|qualityfilter=480p,scr,cam;lang=en|debrid=FAKEKEY"
        const val RAW = "https://torrentio.example/$CONFIG/manifest.json"
        val ESCAPED = RAW.replace("|", "%7C")
        val DOUBLE_ESCAPED = RAW.replace("|", "%257C")
        const val RAW_WITH_QUERY = "https://lists.example/cfg|a=1,b=2/manifest.json?token=FAKE+TOKEN,x=1"

        /** What OkHttp puts on the wire: | becomes %7C, ; = , stay as they are. */
        val CANONICAL = RAW.replace("|", "%7C")
        val CANONICAL_WITH_QUERY = "https://lists.example/cfg%7Ca=1,b=2/manifest.json?token=FAKE+TOKEN,x=1"
    }
}
