package tv.own.owntv.gambit.ui.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.TestAddons.lists
import tv.own.owntv.gambit.addons.TestAddons.meta
import tv.own.owntv.gambit.addons.data.AddonRegistry

/** The access mapping (tests 4–8), its one derivation to [DiscoveryAccess], and the registry each access may use. */
class AddonAccessTest {
    private val approved = setOf("a|h.example|movie|top")

    @Test
    fun `an adult profile is Adult`() {
        assertEquals(AddonAccess.Adult, addonAccessOf(isKids = false, approved = emptySet()))
        assertEquals(AddonAccess.Adult, addonAccessOf(isKids = false, approved = approved, allowKids = true))
    }

    @Test
    fun `a kids profile with no approvals is Closed`() {
        assertEquals(AddonAccess.Closed, addonAccessOf(isKids = true, approved = emptySet(), allowKids = true))
    }

    @Test
    fun `a kids profile with approvals is Kids when the lock allows it`() {
        assertEquals(AddonAccess.Kids(approved, 4), addonAccessOf(isKids = true, approved = approved, allowKids = true, profileId = 4))
    }

    @Test
    fun `with the lock on a kids profile is Closed, and the default is the lock`() {
        assertEquals(AddonAccess.Closed, addonAccessOf(isKids = true, approved = approved, allowKids = false))
        assertEquals(AddonAccess.Closed, addonAccessOf(isKids = true, approved = approved))
    }

    @Test
    fun `a profile that is not read yet is Unknown`() {
        assertEquals(AddonAccess.Unknown, addonAccessOf(isKids = null, approved = approved, allowKids = true))
    }

    @Test
    fun `DiscoveryAccess comes from AddonAccess in one place and Kids never maps to OPEN`() {
        assertEquals(DiscoveryAccess.OPEN, AddonAccess.Adult.toDiscoveryAccess())
        assertEquals(DiscoveryAccess.UNKNOWN, AddonAccess.Unknown.toDiscoveryAccess())
        assertEquals(DiscoveryAccess.KIDS, AddonAccess.Closed.toDiscoveryAccess())
        assertEquals(DiscoveryAccess.KIDS, AddonAccess.Kids(approved).toDiscoveryAccess())
    }

    @Test
    fun `only an adult or approved kids profile may request addon content`() {
        assertTrue(AddonAccess.Adult.isOpen)
        assertTrue(AddonAccess.Kids(approved).isOpen)
        assertFalse(AddonAccess.Closed.isOpen)
        assertFalse(AddonAccess.Unknown.isOpen)
    }

    @Test
    fun `each access gets the registry it may use`() {
        val all = AddonRegistry(listOf(meta, lists))
        val keys = all.browseCatalogs().map { it.key }
        assertEquals(keys, AddonAccess.Adult.registryFrom(all).browseCatalogs().map { it.key })
        assertEquals(listOf(keys[1]), AddonAccess.Kids(setOf(keys[1])).registryFrom(all).browseCatalogs().map { it.key })
        assertTrue(AddonAccess.Closed.registryFrom(all).all.isEmpty())
        assertTrue(AddonAccess.Unknown.registryFrom(all).all.isEmpty())
    }
}
