package tv.own.owntv.gambit.addons

import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.FakeAddonServer.Reply
import tv.own.owntv.gambit.addons.TestAddons.META_BASE
import tv.own.owntv.gambit.addons.TestAddons.lists
import tv.own.owntv.gambit.addons.TestAddons.meta
import tv.own.owntv.gambit.addons.TestAddons.streamsA
import tv.own.owntv.gambit.addons.data.AddonAddError
import tv.own.owntv.gambit.addons.data.AddonAddResult
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.data.AddonStore
import tv.own.owntv.gambit.addons.data.AddonStoreCodec
import tv.own.owntv.gambit.addons.data.AddonVault
import tv.own.owntv.gambit.addons.data.InstalledAddon
import tv.own.owntv.gambit.addons.data.toAddError
import tv.own.owntv.gambit.addons.net.AddonClient
import tv.own.owntv.gambit.addons.net.AddonError
import tv.own.owntv.gambit.addons.net.AddonUrl

class AddonStoreTest {
    /** [text] is what read() returns; [saved] says whether something is stored at all (true with null text = can't be opened). */
    private class MemoryVault(var text: String? = null, var saved: Boolean = text != null, private val failWrites: Boolean = false) : AddonVault {
        var writes = 0
        override fun hasSaved(): Boolean = saved
        override fun read(): String? = text
        override fun write(text: String): Boolean {
            if (failWrites) return false
            this.text = text; saved = true; writes++
            return true
        }
    }

    private val server = FakeAddonServer()
    private val repo = AddonRepository(AddonClient(server.client))
    private val src = TEST_SOURCE

    // --- error mapping -------------------------------------------------------------------------

    @Test
    fun `every addon error maps to what the add screen can say`() {
        val expected = mapOf<AddonError, AddonAddError>(
            AddonError.InvalidUrl to AddonAddError.NOT_AN_ADDRESS,
            AddonError.Timeout(src) to AddonAddError.UNREACHABLE,
            AddonError.Network(src, "UnknownHostException") to AddonAddError.UNREACHABLE,
            AddonError.HttpStatus(src, 400) to AddonAddError.NOT_AN_ADDON,
            AddonError.HttpStatus(src, 401) to AddonAddError.NOT_AN_ADDON,
            AddonError.HttpStatus(src, 404) to AddonAddError.NOT_AN_ADDON,
            AddonError.HttpStatus(src, 499) to AddonAddError.NOT_AN_ADDON,
            AddonError.HttpStatus(src, 500) to AddonAddError.UNREACHABLE,
            AddonError.HttpStatus(src, 503) to AddonAddError.UNREACHABLE,
            AddonError.MalformedJson(src) to AddonAddError.NOT_AN_ADDON,
            AddonError.MissingField(src, "name") to AddonAddError.NOT_AN_ADDON,
            AddonError.NotFound(src) to AddonAddError.NOT_AN_ADDON,
            AddonError.NoAddon("meta", "movie") to AddonAddError.NOT_AN_ADDON,
        )
        expected.forEach { (error, add) -> assertEquals(error.toString(), add, error.toAddError()) }
    }

    // --- save / load round trip ----------------------------------------------------------------

    @Test
    fun `saved list loads back the same, in order, with on and off kept`() {
        val withQuery = InstalledAddon(AddonUrl.parse("https://q.example/KEY-Q/manifest.json?token=KEY-QUERY")!!, TestAddons.manifest("manifest_odd.json"), enabled = false)
        val list = listOf(meta, lists, streamsA.copy(enabled = false), withQuery)
        assertEquals(list, AddonStoreCodec.decode(AddonStoreCodec.encode(list)))
    }

    @Test
    fun `fields the parser drops, like config, are never saved`() {
        val raw = """{"id":"a.b","name":"A","version":"1","resources":["stream"],"types":["movie"],
            "config":[{"key":"debridKey","default":"SECRET-CONFIG"}],
            "behaviorHints":{"configurable":true,"apiKey":"SECRET-HINT"}}"""
        val manifest = (tv.own.owntv.gambit.addons.net.AddonJson.manifest(raw, src) as tv.own.owntv.gambit.addons.net.AddonResult.Ok).value
        val text = AddonStoreCodec.encode(listOf(InstalledAddon(AddonUrl.parse("https://a.example/manifest.json")!!, manifest)))
        assertFalse(text, text.contains("SECRET"))
        assertFalse(text, text.contains("config\""))
    }

    @Test
    fun `unreadable saved text is null, and a bad entry is dropped`() {
        assertNull(AddonStoreCodec.decode("not json"))
        assertNull(AddonStoreCodec.decode("""{"v":99,"addons":[]}"""))
        val good = AddonStoreCodec.encode(listOf(meta))
        val mixed = good.replace("\"addons\":[", "\"addons\":[{\"url\":\"nope\"},")
        assertEquals(listOf(meta), AddonStoreCodec.decode(mixed))
    }

    // --- the store -----------------------------------------------------------------------------

    @Test
    fun `adding saves the list, and a new store loads it back`() = runBlocking {
        server.onFixture("$META_BASE/manifest.json", "manifest_meta.json")
        val vault = MemoryVault()
        assertEquals(AddonAddResult.Added("Example Meta"), AddonStore(repo, vault).add("$META_BASE/manifest.json"))
        assertEquals(listOf(meta), AddonStore(repo, vault).registry.value.all)
    }

    @Test
    fun `adding the same address again keeps its place and its off state`() = runBlocking {
        server.onFixture("$META_BASE/manifest.json", "manifest_meta.json")
        val vault = MemoryVault(AddonStoreCodec.encode(listOf(lists, meta.copy(enabled = false))))
        val store = AddonStore(repo, vault)
        store.add("stremio://meta.example/KEY-META")
        assertEquals(listOf(lists, meta.copy(enabled = false)), store.registry.value.all)
    }

    @Test
    fun `a failed add says why and saves nothing`() = runBlocking {
        server.on("$META_BASE/manifest.json", Reply.Fail(SocketTimeoutException("timeout")))
        val vault = MemoryVault()
        val store = AddonStore(repo, vault)
        assertEquals(AddonAddResult.Failed(AddonAddError.UNREACHABLE), store.add("$META_BASE/manifest.json"))
        assertEquals(AddonAddResult.Failed(AddonAddError.NOT_AN_ADDRESS), store.add("ftp://x"))
        server.on("$META_BASE/manifest.json", Reply.Body("{}", code = 404))
        assertEquals(AddonAddResult.Failed(AddonAddError.NOT_AN_ADDON), store.add("$META_BASE/manifest.json"))
        server.on("$META_BASE/manifest.json", Reply.Body("{}", code = 503))
        assertEquals(AddonAddResult.Failed(AddonAddError.UNREACHABLE), store.add("$META_BASE/manifest.json"))
        assertEquals(0, vault.writes)
        assertNull(vault.text)
    }

    @Test
    fun `nothing saved is an empty list, not an unreadable one`() {
        val store = AddonStore(repo, MemoryVault())
        assertTrue(store.registry.value.all.isEmpty())
        assertFalse(store.unreadable.value)
    }

    @Test
    fun `a lost key or damaged data is flagged as unreadable without crashing`() {
        val lostKey = AddonStore(repo, MemoryVault(text = null, saved = true))
        assertTrue(lostKey.registry.value.all.isEmpty())
        assertTrue(lostKey.unreadable.value)
        assertTrue(AddonStore(repo, MemoryVault(text = "damaged")).unreadable.value)
    }

    @Test
    fun `the unreadable flag clears once a new list is saved, not before`() = runBlocking {
        server.onFixture("$META_BASE/manifest.json", "manifest_meta.json")
        val failing = AddonStore(repo, MemoryVault(text = null, saved = true, failWrites = true))
        failing.add("$META_BASE/manifest.json")
        assertTrue(failing.unreadable.value)
        val store = AddonStore(repo, MemoryVault(text = null, saved = true))
        store.add("$META_BASE/manifest.json")
        assertFalse(store.unreadable.value)
        assertEquals(listOf(meta), store.registry.value.all)
    }

    @Test
    fun `remove, turn off and move are saved`() {
        val vault = MemoryVault(AddonStoreCodec.encode(listOf(meta, lists, streamsA)))
        val store = AddonStore(repo, vault)
        store.move(streamsA.url, -1)
        store.setEnabled(lists.url, false)
        store.remove(meta.url)
        store.move(streamsA.url, -1) // already first: nothing to save
        val expected = listOf(streamsA, lists.copy(enabled = false))
        assertEquals(expected, store.registry.value.all)
        assertEquals(expected, AddonStoreCodec.decode(vault.text!!))
        assertEquals(3, vault.writes)
    }
}
