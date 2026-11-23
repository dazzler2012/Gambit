package tv.own.owntv.gambit.addons

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.TestAddons.lists
import tv.own.owntv.gambit.addons.TestAddons.meta
import tv.own.owntv.gambit.addons.TestAddons.streamsA
import tv.own.owntv.gambit.addons.TestAddons.streamsB
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.CatalogKeys
import tv.own.owntv.gambit.addons.data.KidsAccessCodec
import tv.own.owntv.gambit.addons.data.KidsAccessPruner
import tv.own.owntv.gambit.addons.data.KidsAccessRelease
import tv.own.owntv.gambit.addons.data.KidsAccessVault
import tv.own.owntv.gambit.addons.data.KidsAddonAccess
import tv.own.owntv.gambit.addons.data.beforeProfileEdit

class KidsAddonAccessTest {
    /** Stands in for the encrypted vault; every write and removal is on "disk" (these fields) when it returns. */
    private class MemoryVault(var text: String? = null, var saved: Boolean = text != null) : KidsAccessVault {
        var failWrites = false
        var throwOnRead = false
        var writes = 0
        var removes = 0
        override fun hasSaved(): Boolean = saved
        override fun read(): String? = if (throwOnRead) throw IllegalStateException("keystore") else text
        override fun write(text: String): Boolean {
            if (failWrites) return false
            this.text = text; saved = true; writes++
            return true
        }
        override fun remove(): Boolean {
            if (failWrites) return false
            text = null; saved = false; removes++
            return true
        }
    }

    private val registry = AddonRegistry(listOf(meta, lists, streamsA, streamsB))
    private val keys = registry.browseCatalogs().map { it.key } // three browse catalogues, in order

    private fun access(vault: KidsAccessVault) = KidsAddonAccess(vault, Dispatchers.Unconfined)

    private fun stored(vault: MemoryVault): Map<Long, Set<String>> =
        vault.text?.let(KidsAccessCodec::decode).orEmpty()

    // --- 1. store round trip, nothing sensitive stored -------------------------------------------

    @Test
    fun `release lock is lifted`() {
        assertTrue(KidsAccessRelease.ALLOWED)
    }

    @Test
    fun `approvals save and read back, and nothing stored holds an addon path or key`() = runBlocking {
        val vault = MemoryVault()
        val a = access(vault)
        assertTrue(a.set(7, setOf(keys[0], keys[2])))
        assertTrue(a.set(9, setOf(keys[1])))

        val again = access(vault)
        assertEquals(setOf(keys[0], keys[2]), again.approved(7))
        assertEquals(setOf(keys[1]), again.approved(9))
        assertEquals(emptySet<String>(), again.approved(8))

        val text = vault.text!!
        listOf("KEY-META", "KEY-LISTS", "KEY-A", "KEY-B", "manifest.json", "https://", "example", "org.example").forEach {
            assertFalse("stored text holds $it", text.contains(it))
        }
        // The key is made of the format version, the random setup id, type and catalogue id only.
        assertTrue(keys.all(CatalogKeys::isValid))
        assertTrue(keys.all { it.split("|").size == 4 })
    }

    // --- old and foreign keys --------------------------------------------------------------------

    @Test
    fun `approvals saved in the old format are gone, and the first watcher pass deletes them`() = runBlocking {
        val old = """{"v":1,"profiles":{"7":["org.example.meta|meta.example|movie|popular"]}}"""
        val vault = MemoryVault(text = old)
        val a = access(vault)
        assertEquals(emptySet<String>(), a.approved(7))
        assertTrue(vault.saved) // still on disk until the watcher runs

        KidsAccessPruner(a, kotlinx.coroutines.flow.emptyFlow(), kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)).prune(setOf(7))

        assertFalse(vault.saved)
        assertEquals(1, vault.removes)
    }

    @Test
    fun `old-format keys mixed into the current format are dropped and the rest is saved again`() = runBlocking {
        val mixed = KidsAccessCodec.encode(mapOf(7L to setOf(keys[0]))).replace("[", "[\"org.example.meta|meta.example|movie|popular\",")
        val vault = MemoryVault(text = mixed)
        val a = access(vault)
        assertEquals(setOf(keys[0]), a.approved(7))
        assertTrue(a.flushIfDirty())
        assertEquals(mapOf(7L to setOf(keys[0])), stored(vault))
        assertFalse(vault.text!!.contains("meta.example"))
    }

    @Test
    fun `set ignores keys that are not current catalogue keys`() = runBlocking {
        val vault = MemoryVault()
        val a = access(vault)
        a.set(7, setOf("org.example.meta|meta.example|movie|popular", "2|not-a-setup|movie|x", keys[1]))
        assertEquals(setOf(keys[1]), a.approved(7))
        a.set(8, setOf("org.example.meta|meta.example|movie|popular"))
        assertEquals(emptySet<String>(), a.approved(8))
        assertEquals(setOf(7L), stored(vault).keys)
    }

    @Test
    fun `retainSetups drops approvals of setups that are gone on every kids profile and keeps the rest`() = runBlocking {
        val vault = MemoryVault()
        val a = access(vault)
        val metaKeys = registry.browseCatalogs().filter { it.addon.setupId == meta.setupId }.map { it.key }.toSet()
        val listsKeys = registry.browseCatalogs().filter { it.addon.setupId == lists.setupId }.map { it.key }.toSet()
        assertTrue(metaKeys.isNotEmpty() && listsKeys.isNotEmpty())
        a.set(1, metaKeys + listsKeys); a.set(2, metaKeys)
        val writes = vault.writes

        assertTrue(a.retainSetups(setOf(lists.setupId, streamsA.setupId)))

        assertEquals(listsKeys, a.approved(1))
        assertEquals(emptySet<String>(), a.approved(2))
        assertEquals(mapOf(1L to listsKeys), stored(vault))
        assertEquals(writes + 1, vault.writes)

        assertTrue(a.retainSetups(setOf(lists.setupId))) // nothing to drop: no write
        assertEquals(writes + 1, vault.writes)
    }

    // --- 2. unreadable vault ---------------------------------------------------------------------

    @Test
    fun `an unreadable vault gives no approvals`() {
        assertEquals(emptySet<String>(), access(MemoryVault(text = null, saved = true)).approved(7))
        assertEquals(emptySet<String>(), access(MemoryVault(text = "not json at all")).approved(7))
        assertEquals(emptySet<String>(), access(MemoryVault(text = """{"v":2,"profiles":{"7":["x"]}}""")).approved(7))
    }

    @Test
    fun `a vault that throws on read builds fine with no approvals and still saves later`() = runBlocking {
        val vault = MemoryVault(text = KidsAccessCodec.encode(mapOf(7L to setOf(keys[0]))))
        vault.throwOnRead = true

        val a = access(vault) // must not throw
        assertEquals(emptySet<String>(), a.approved(7))
        assertTrue(a.profileIds().isEmpty())

        vault.throwOnRead = false
        assertTrue(a.set(8, setOf(keys[1])))
        assertEquals(setOf(keys[1]), a.approved(8))
        assertEquals(setOf(keys[1]), access(vault).approved(8))
    }

    // --- 3. limitedTo ----------------------------------------------------------------------------

    @Test
    fun `limitedTo keeps only approved browse catalogues in addon order`() {
        val limited = registry.limitedTo(setOf(keys[2], keys[0], "gone|x.example|movie|stale"))
        assertEquals(listOf(keys[0], keys[2]), limited.browseCatalogs().map { it.key })
        assertTrue(registry.limitedTo(emptySet()).browseCatalogs().isEmpty())
        assertEquals(keys, registry.browseCatalogs().map { it.key }) // the original is not changed
    }

    @Test
    fun `limitedTo leaves meta and stream lookups alone`() {
        val limited = registry.limitedTo(setOf(keys[0]))
        assertTrue(registry.metaAddons("movie", "tt1").isNotEmpty())
        assertEquals(registry.metaAddons("movie", "tt1").map { it.url }, limited.metaAddons("movie", "tt1").map { it.url })
        assertEquals(registry.streamAddons("movie", "tt1").map { it.url }, limited.streamAddons("movie", "tt1").map { it.url })
        assertEquals(registry.all, limited.all)
    }

    @Test
    fun `a limited registry offers no search catalogues and an unlimited one is unchanged`() {
        assertEquals(listOf("search", "list.trending"), registry.searchCatalogs().map { it.catalog.id })
        assertTrue(registry.limitedTo(keys.toSet()).searchCatalogs().isEmpty())
        assertTrue(registry.limitedTo(setOf(keys[0])).searchCatalogs("series").isEmpty())
        assertTrue(registry.limitedTo(emptySet()).searchCatalogs().isEmpty())
    }

    @Test
    fun `the limit survives registry changes and limiting twice only narrows`() {
        val limited = registry.limitedTo(setOf(keys[0], keys[1]))
        assertEquals(listOf(keys[0], keys[1]), limited.withEnabled(streamsA.url, false).browseCatalogs().map { it.key })
        assertEquals(listOf(keys[1]), limited.limitedTo(setOf(keys[1], keys[2])).browseCatalogs().map { it.key })
    }

    // --- 9. pruning on profile delete ------------------------------------------------------------

    @Test
    fun `the watcher removes the entry of a deleted profile and keeps the others`() = runBlocking {
        val vault = MemoryVault()
        val a = access(vault)
        a.set(1, setOf(keys[0])); a.set(2, setOf(keys[1]))

        KidsAccessPruner(a, kotlinx.coroutines.flow.emptyFlow(), kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined))
            .prune(setOf(2)) // profile 1 is gone

        assertEquals(emptySet<String>(), a.approved(1))
        assertEquals(setOf(keys[1]), a.approved(2))
        assertEquals(mapOf(2L to setOf(keys[1])), stored(vault))
    }

    // --- 10, 11. Kids off, then on, through the hook ---------------------------------------------

    @Test
    fun `kids off then on through the hook starts closed with no stored entry`() = runBlocking {
        val vault = MemoryVault()
        val a = access(vault)
        a.set(1, setOf(keys[0]))

        beforeProfileEdit(a, 1, isKids = false)
        beforeProfileEdit(a, 1, isKids = true)

        assertEquals(emptySet<String>(), a.approved(1))
        assertFalse(vault.saved)
        assertEquals(emptySet<String>(), access(vault).approved(1))
    }

    @Test
    fun `a quick off then on keeps other profiles and a fresh read has no entry for this one`() = runBlocking {
        val vault = MemoryVault()
        val a = access(vault)
        a.set(1, setOf(keys[0])); a.set(2, setOf(keys[1]))

        beforeProfileEdit(a, 1, false)
        beforeProfileEdit(a, 1, true)

        val fresh = access(vault)
        assertEquals(emptySet<String>(), fresh.approved(1))
        assertEquals(setOf(keys[1]), fresh.approved(2))
    }

    // --- 12, 13. clearNow ------------------------------------------------------------------------

    @Test
    fun `clearNow has finished the write when it returns`() = runBlocking {
        val vault = MemoryVault()
        val a = access(vault)
        a.set(1, setOf(keys[0])); a.set(2, setOf(keys[1]))
        val writesBefore = vault.writes

        assertTrue(a.clearNow(1))

        assertEquals(writesBefore + 1, vault.writes) // already on disk, nothing pending
        assertEquals(mapOf(2L to setOf(keys[1])), stored(vault))
    }

    @Test
    fun `a failed write still clears memory and clearNow says false`() = runBlocking {
        val vault = MemoryVault()
        val a = access(vault)
        a.set(1, setOf(keys[0]))
        vault.failWrites = true

        assertFalse(a.clearNow(1))

        assertEquals(emptySet<String>(), a.approved(1))
        assertEquals(setOf(keys[0]), stored(vault)[1]) // the stale copy is still on disk
    }

    // --- 14. Kids on does not touch storage ------------------------------------------------------

    @Test
    fun `beforeProfileEdit with Kids on does not touch storage or approvals`() = runBlocking {
        val vault = MemoryVault()
        val a = access(vault)
        a.set(1, setOf(keys[0]))
        val writes = vault.writes
        val removes = vault.removes

        beforeProfileEdit(a, 1, isKids = true)
        beforeProfileEdit(a, 5, isKids = true)

        assertEquals(writes, vault.writes)
        assertEquals(removes, vault.removes)
        assertEquals(setOf(keys[0]), a.approved(1))
    }

    @Test
    fun `clearing a profile with no entry writes nothing`() = runBlocking {
        val vault = MemoryVault()
        val a = access(vault)
        assertTrue(a.clearNow(3))
        assertEquals(0, vault.writes + vault.removes)
    }

    // --- 15. the watcher ---------------------------------------------------------------------------

    @Test
    fun `the watcher clears missing or non-kids profiles and retries after a failed write`() = runBlocking {
        val vault = MemoryVault()
        val a = access(vault)
        a.set(1, setOf(keys[0])); a.set(2, setOf(keys[1])); a.set(3, setOf(keys[2]))
        val pruner = KidsAccessPruner(a, kotlinx.coroutines.flow.emptyFlow(), kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined))

        vault.failWrites = true
        pruner.prune(setOf(3)) // 1 deleted, 2 turned adult; the write fails
        assertEquals(setOf(3L), a.profileIds())
        assertEquals(setOf(1L, 2L, 3L), stored(vault).keys) // disk still has them

        vault.failWrites = false
        pruner.prune(setOf(3)) // nothing left to clear, but the failed write is tried again
        assertEquals(mapOf(3L to setOf(keys[2])), stored(vault))
        assertNull(vault.text?.takeIf { it.contains(keys[0]) })
    }

    @Test
    fun `the watcher clears a stale entry loaded from disk after a restart`() = runBlocking {
        val vault = MemoryVault(text = KidsAccessCodec.encode(mapOf(4L to setOf(keys[0]))))
        val a = access(vault)
        assertEquals(setOf(keys[0]), a.approved(4)) // loaded, until the watcher has seen the profile list
        KidsAccessPruner(a, kotlinx.coroutines.flow.emptyFlow(), kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)).prune(emptySet())
        assertEquals(emptySet<String>(), a.approved(4))
        assertFalse(vault.saved)
    }
}
