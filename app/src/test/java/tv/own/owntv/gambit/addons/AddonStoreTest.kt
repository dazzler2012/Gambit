package tv.own.owntv.gambit.addons

import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import tv.own.owntv.gambit.addons.data.AddonSetupId
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

    /** For comparing addons whose setup id was made at random by a real install. */
    private fun List<InstalledAddon>.sansSetup() = map { it.copy(setupId = "") }

    /** [text] with every entry's setup id taken out, as lists were saved before setup ids existed. */
    private fun withoutSetupIds(text: String): String {
        val root = org.json.JSONObject(text)
        val items = root.getJSONArray("addons")
        for (i in 0 until items.length()) items.getJSONObject(i).remove("setup")
        return root.toString()
    }

    private fun setupIds(store: AddonStore) = store.registry.value.all.map { it.setupId }

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
        val withQuery = InstalledAddon(AddonUrl.parse("https://q.example/KEY-Q/manifest.json?token=KEY-QUERY")!!, TestAddons.manifest("manifest_odd.json"), enabled = false, setupId = TestAddons.setupId("q"))
        val list = listOf(meta, lists, streamsA.copy(enabled = false), withQuery)
        assertEquals(list, AddonStoreCodec.decode(AddonStoreCodec.encode(list)))
    }

    @Test
    fun `fields the parser drops, like config, are never saved`() {
        val raw = """{"id":"a.b","name":"A","version":"1","resources":["stream"],"types":["movie"],
            "config":[{"key":"debridKey","default":"SECRET-CONFIG"}],
            "behaviorHints":{"configurable":true,"apiKey":"SECRET-HINT"}}"""
        val manifest = (tv.own.owntv.gambit.addons.net.AddonJson.manifest(raw, src) as tv.own.owntv.gambit.addons.net.AddonResult.Ok).value
        val text = AddonStoreCodec.encode(listOf(InstalledAddon(AddonUrl.parse("https://a.example/manifest.json")!!, manifest, setupId = TestAddons.setupId("a"))))
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
        val first = AddonStore(repo, vault)
        assertEquals(AddonAddResult.Added("Example Meta"), first.add("$META_BASE/manifest.json"))
        assertEquals(listOf(meta).sansSetup(), AddonStore(repo, vault).registry.value.all.sansSetup())
        assertEquals(first.registry.value.all, AddonStore(repo, vault).registry.value.all) // setup id included
    }

    @Test
    fun `adding the same address again keeps its place and its off state, as a new setup`() = runBlocking {
        server.onFixture("$META_BASE/manifest.json", "manifest_meta.json")
        val vault = MemoryVault(AddonStoreCodec.encode(listOf(lists, meta.copy(enabled = false))))
        val store = AddonStore(repo, vault)
        store.add("stremio://meta.example/KEY-META")
        assertEquals(listOf(lists, meta.copy(enabled = false)).sansSetup(), store.registry.value.all.sansSetup())
        assertEquals(lists.setupId, store.registry.value.all[0].setupId)
        assertNotEquals(meta.setupId, store.registry.value.all[1].setupId)
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
        assertEquals(listOf(meta).sansSetup(), store.registry.value.all.sansSetup())
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

    // --- setup ids -----------------------------------------------------------------------------

    @Test
    fun `a list saved before setup ids gets them, saves them at once, and keeps them after a restart`() {
        val vault = MemoryVault(withoutSetupIds(AddonStoreCodec.encode(listOf(meta, lists, streamsA))))
        val first = AddonStore(repo, vault)
        val ids = setupIds(first)
        assertTrue(ids.all(AddonSetupId::isValid))
        assertEquals(3, ids.toSet().size)
        assertEquals(1, vault.writes)
        assertEquals(listOf(meta, lists, streamsA).sansSetup(), first.registry.value.all.sansSetup())

        val again = AddonStore(repo, vault)
        assertEquals(ids, setupIds(again))
        assertEquals(1, vault.writes) // nothing new to save
        // Catalogue keys (what kids approvals hold) are the same after the restart.
        assertEquals(first.registry.value.browseCatalogs().map { it.key }, again.registry.value.browseCatalogs().map { it.key })
    }

    @Test
    fun `a damaged or repeated setup id is replaced and saved, the good one is kept`() {
        val root = org.json.JSONObject(AddonStoreCodec.encode(listOf(meta, lists, streamsA)))
        val items = root.getJSONArray("addons")
        items.getJSONObject(0).put("setup", "not-an-id")
        items.getJSONObject(2).put("setup", lists.setupId) // a repeat of the entry before it
        val vault = MemoryVault(root.toString())
        val ids = setupIds(AddonStore(repo, vault))
        assertNotEquals("not-an-id", ids[0])
        assertTrue(AddonSetupId.isValid(ids[0]))
        assertEquals(lists.setupId, ids[1])
        assertNotEquals(lists.setupId, ids[2])
        assertEquals(1, vault.writes)
        assertEquals(ids, setupIds(AddonStore(repo, vault)))
    }

    @Test
    fun `a list whose setup ids are all good is not saved again on load`() {
        val vault = MemoryVault(AddonStoreCodec.encode(listOf(meta, lists)))
        assertEquals(listOf(meta.setupId, lists.setupId), setupIds(AddonStore(repo, vault)))
        assertEquals(0, vault.writes)
    }

    @Test
    fun `if saving minted ids fails the list still loads, and the next start mints new ones`() {
        val text = withoutSetupIds(AddonStoreCodec.encode(listOf(meta)))
        val vault = MemoryVault(text, failWrites = true)
        val first = setupIds(AddonStore(repo, vault))
        val second = setupIds(AddonStore(repo, vault))
        assertTrue(first.all(AddonSetupId::isValid))
        assertNotEquals(first, second) // fails closed: approvals tied to the first ids no longer match
        assertEquals(text, vault.text)
    }

    @Test
    fun `the saved list holds each addon's setup id and reads it back`() {
        val text = AddonStoreCodec.encode(listOf(meta))
        assertTrue(text.contains(meta.setupId))
        assertEquals(listOf(meta), AddonStoreCodec.decode(text))
    }
}
