package tv.own.owntv.gambit.ui.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.TestAddons.lists
import tv.own.owntv.gambit.addons.TestAddons.meta
import tv.own.owntv.gambit.addons.TestAddons.streamsA
import tv.own.owntv.gambit.addons.data.AddonRegistry

class KidsAccessEntryTest {
    @Test
    fun `the Kids access entry is hidden while the lock is on, whatever the profile`() {
        assertFalse(kidsAccessEntryVisible(kidsProfile = false, allowKids = false))
        assertFalse(kidsAccessEntryVisible(kidsProfile = true, allowKids = false))
    }

    @Test
    fun `by default the entry shows on adult profiles only`() {
        assertTrue(kidsAccessEntryVisible(kidsProfile = false))
        assertFalse(kidsAccessEntryVisible(kidsProfile = true))
    }

    @Test
    fun `with the lock lifted the entry shows on adult profiles only`() {
        assertTrue(kidsAccessEntryVisible(kidsProfile = false, allowKids = true))
        assertFalse(kidsAccessEntryVisible(kidsProfile = true, allowKids = true))
    }

    @Test
    fun `catalogues are grouped by addon in addon order and carry only a key, name and type`() {
        val registry = AddonRegistry(listOf(meta, lists, streamsA))
        val groups = catalogGroups(registry)
        assertEquals(listOf("Example Meta", "Example Lists"), groups.map { it.addonName })
        assertEquals(registry.browseCatalogs().map { it.key }, groups.flatMap { it.catalogs }.map { it.key })
        val shown = groups.flatMap { g -> g.catalogs.map { "${it.name}${it.type}${it.key}" } }.joinToString()
        assertFalse(shown.contains("KEY-"))
        assertFalse(shown.contains("manifest.json"))
    }

    @Test
    fun `a disabled addon offers nothing to approve`() {
        val registry = AddonRegistry(listOf(meta, lists)).withEnabled(meta.url, false)
        assertEquals(listOf("Example Lists"), catalogGroups(registry).map { it.addonName })
    }
}
