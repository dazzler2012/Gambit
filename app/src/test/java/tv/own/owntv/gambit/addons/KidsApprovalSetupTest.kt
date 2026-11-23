package tv.own.owntv.gambit.addons

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.TestAddons.LISTS_BASE
import tv.own.owntv.gambit.addons.TestAddons.META_BASE
import tv.own.owntv.gambit.addons.TestAddons.lists
import tv.own.owntv.gambit.addons.TestAddons.meta
import tv.own.owntv.gambit.addons.TestAddons.streamsA
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.data.AddonStore
import tv.own.owntv.gambit.addons.data.AddonStoreCodec
import tv.own.owntv.gambit.addons.data.AddonVault
import tv.own.owntv.gambit.addons.data.KidsAccessCodec
import tv.own.owntv.gambit.addons.data.KidsAccessPruner
import tv.own.owntv.gambit.addons.data.KidsAccessVault
import tv.own.owntv.gambit.addons.data.KidsAddonAccess
import tv.own.owntv.gambit.addons.data.setupIds
import tv.own.owntv.gambit.addons.net.AddonClient
import tv.own.owntv.gambit.ui.discovery.AddonAccess
import tv.own.owntv.gambit.ui.discovery.registryFrom

/** Kids approvals belong to one exact addon setup: another setup, a re-add or a removal never keeps them. */
class KidsApprovalSetupTest {
    private class ListVault(var text: String? = null, var saved: Boolean = text != null) : AddonVault {
        var writes = 0
        override fun hasSaved(): Boolean = saved
        override fun read(): String? = text
        override fun write(text: String): Boolean { this.text = text; saved = true; writes++; return true }
    }

    private class ApprovalVault(var text: String? = null) : KidsAccessVault {
        var writes = 0
        var removes = 0
        override fun hasSaved(): Boolean = text != null
        override fun read(): String? = text
        override fun write(text: String): Boolean { this.text = text; writes++; return true }
        override fun remove(): Boolean { text = null; removes++; return true }
    }

    private val server = FakeAddonServer()
        .onFixture("$META_BASE/manifest.json", "manifest_meta.json")
        .onFixture("$OTHER_META_BASE/manifest.json", "manifest_meta.json")
        .onFixture("$LISTS_BASE/manifest.json", "manifest_lists.json")
    private val repo = AddonRepository(AddonClient(server.client))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private fun store(vararg start: tv.own.owntv.gambit.addons.data.InstalledAddon) =
        AddonStore(repo, ListVault(AddonStoreCodec.encode(start.toList())))

    private fun access(vault: ApprovalVault = ApprovalVault()) = KidsAddonAccess(vault, Dispatchers.Unconfined)

    /** The watcher as the app wires it: kids profiles [KID] and [KID2], addon setups from [store]'s registry. */
    private fun startPruner(access: KidsAddonAccess, store: AddonStore) =
        KidsAccessPruner(access, MutableStateFlow(setOf(KID, KID2)), scope, store.registry.map { it.setupIds() }).also { it.start() }

    /** The browse catalogue keys of the installed addon with this [setupId]. */
    private fun keysOf(store: AddonStore, setupId: String) =
        store.registry.value.browseCatalogs().filter { it.addon.setupId == setupId }.map { it.key }.toSet()

    /** What a kids profile's Discovery lists, worked out the way DiscoveryViewModel does. */
    private fun kidsSees(store: AddonStore, access: KidsAddonAccess, profile: Long = KID) =
        AddonAccess.Kids(access.approved(profile), profile).registryFrom(store.registry.value).browseCatalogs().map { it.key }

    // --- app start -------------------------------------------------------------------------------

    @Test
    fun `app start with stored approvals and installed addons keeps the approvals and writes nothing`() {
        val store = store(meta, lists)
        val approved = keysOf(store, meta.setupId)
        val vault = ApprovalVault(KidsAccessCodec.encode(mapOf(KID to approved, KID2 to keysOf(store, lists.setupId))))
        val a = access(vault)

        startPruner(a, store)

        assertEquals(approved, a.approved(KID))
        assertEquals(keysOf(store, lists.setupId), a.approved(KID2))
        assertEquals(0, vault.writes + vault.removes)
        assertEquals(approved.sorted(), kidsSees(store, a).sorted())
    }

    @Test
    fun `app start with an addon list from before setup ids keeps nothing approved, and later starts are stable`() {
        // Approvals made against ids minted on the first start still match after a restart.
        val text = AddonStoreCodec.encode(listOf(meta))
        val list = ListVault(org.json.JSONObject(text).apply { getJSONArray("addons").getJSONObject(0).remove("setup") }.toString())
        val first = AddonStore(repo, list)
        val a = access()
        runBlocking { a.set(KID, keysOf(first, first.registry.value.all[0].setupId)) }
        val approved = a.approved(KID)
        assertTrue(approved.isNotEmpty())

        val again = AddonStore(repo, list)
        startPruner(a, again)
        assertEquals(approved, a.approved(KID))
        assertEquals(approved.sorted(), kidsSees(again, a).sorted())
    }

    @Test
    fun `a loaded list that is genuinely empty clears every approval`() {
        val withMeta = store(meta)
        val vault = ApprovalVault(KidsAccessCodec.encode(mapOf(KID to keysOf(withMeta, meta.setupId))))
        val a = access(vault)

        startPruner(a, store()) // the saved list is empty

        assertEquals(emptySet<String>(), a.approved(KID))
        assertEquals(null, vault.text)
    }

    @Test
    fun `a list that can't be read clears every approval`() {
        val withMeta = store(meta)
        val vault = ApprovalVault(KidsAccessCodec.encode(mapOf(KID to keysOf(withMeta, meta.setupId))))
        val a = access(vault)
        val unreadable = AddonStore(repo, ListVault(text = null, saved = true))
        assertTrue(unreadable.unreadable.value)

        startPruner(a, unreadable)

        assertEquals(emptySet<String>(), a.approved(KID))
        assertEquals(null, vault.text)
    }

    // --- changes that keep the setup ----------------------------------------------------------------

    @Test
    fun `turning an addon off and on, and moving it, keep its setup id and its kids approvals`() {
        val store = store(meta, lists, streamsA)
        val a = access()
        val approved = keysOf(store, meta.setupId)
        runBlocking { a.set(KID, approved) }
        startPruner(a, store)

        store.setEnabled(meta.url, false)
        assertEquals(meta.setupId, store.registry.value.all.first { it.url == meta.url }.setupId)
        assertEquals(approved, a.approved(KID))
        assertTrue(kidsSees(store, a).isEmpty()) // off: nothing shown, approval kept

        store.setEnabled(meta.url, true)
        store.move(meta.url, +1)
        store.move(meta.url, +1)
        assertEquals(meta.url, store.registry.value.all.last().url)
        assertEquals(meta.setupId, store.registry.value.all.last().setupId)
        assertEquals(approved, a.approved(KID))
        assertEquals(approved.sorted(), kidsSees(store, a).sorted())
    }

    // --- other setups start unapproved -----------------------------------------------------------

    @Test
    fun `a second configuration of an approved addon is unapproved`() = runBlocking {
        val store = store(meta)
        val a = access()
        val approved = keysOf(store, meta.setupId)
        a.set(KID, approved)
        startPruner(a, store)

        store.add("$OTHER_META_BASE/manifest.json")

        val other = store.registry.value.all.first { it.url != meta.url }
        assertEquals(meta.manifest.id, other.manifest.id) // same addon, same host, other configuration
        assertNotEquals(meta.setupId, other.setupId)
        assertTrue(keysOf(store, other.setupId).none { it in a.approved(KID) })
        assertEquals(approved.sorted(), kidsSees(store, a).sorted())
    }

    @Test
    fun `removing an addon prunes its approvals from every kids profile, and re-adding it starts unapproved`() = runBlocking {
        val vault = ApprovalVault()
        val store = store(meta, lists)
        val a = access(vault)
        val metaKeys = keysOf(store, meta.setupId)
        val listsKeys = keysOf(store, lists.setupId)
        a.set(KID, metaKeys + listsKeys)
        a.set(KID2, metaKeys)
        startPruner(a, store)

        store.remove(meta.url)

        assertEquals(listsKeys, a.approved(KID))
        assertEquals(emptySet<String>(), a.approved(KID2))
        assertEquals(mapOf(KID to listsKeys), KidsAccessCodec.decode(vault.text!!))

        store.add("$META_BASE/manifest.json") // the very same address
        val again = store.registry.value.all.first { it.url == meta.url }
        assertNotEquals(meta.setupId, again.setupId)
        assertTrue(kidsSees(store, a, KID2).isEmpty())
        assertEquals(listsKeys.sorted(), kidsSees(store, a).sorted())
    }

    @Test
    fun `re-adding the address of an approved addon in place starts it unapproved`() = runBlocking {
        val store = store(meta)
        val a = access()
        a.set(KID, keysOf(store, meta.setupId))
        startPruner(a, store)

        store.add("$META_BASE/manifest.json") // replaces it in place, as a new setup

        assertEquals(emptySet<String>(), a.approved(KID))
        assertTrue(kidsSees(store, a).isEmpty())
    }

    @Test
    fun `a stale approval with no watcher running still never shows a re-added addon`() = runBlocking {
        val store = store(meta, lists)
        val a = access()
        a.set(KID, keysOf(store, lists.setupId))

        store.remove(lists.url)
        store.add("$LISTS_BASE/manifest.json")

        assertTrue(a.approved(KID).isNotEmpty()) // nothing pruned it
        assertTrue(kidsSees(store, a).isEmpty())
    }

    // --- nothing stored holds an address --------------------------------------------------------

    @Test
    fun `stored approvals hold no address, host, path or key`() = runBlocking {
        val vault = ApprovalVault()
        val store = store(meta, lists, streamsA)
        val a = access(vault)
        a.set(KID, store.registry.value.browseCatalogs().map { it.key }.toSet())
        val text = vault.text!!
        listOf("KEY-META", "KEY-LISTS", "KEY-A", "KEY-", "manifest", "https", "example", "org.example").forEach {
            assertFalse("stored approvals hold $it", text.contains(it))
        }
        // Pruning while every setup is still installed changes nothing.
        KidsAccessPruner(a, emptyFlow(), scope).pruneSetups(store.registry.value.setupIds())
        assertEquals(store.registry.value.browseCatalogs().map { it.key }.toSet(), a.approved(KID))
    }

    private companion object {
        const val KID = 7L
        const val KID2 = 8L
        const val OTHER_META_BASE = "https://meta.example/OTHER-CONFIG"
    }
}
