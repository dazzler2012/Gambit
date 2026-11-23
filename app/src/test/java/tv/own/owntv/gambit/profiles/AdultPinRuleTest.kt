package tv.own.owntv.gambit.profiles

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ProfileEntity

/** When a kids profile needs an adult PIN, and how one PIN is checked against several adult profiles. */
class AdultPinRuleTest {

    private fun profile(id: Long, kids: Boolean = false) = ProfileEntity(
        id = id,
        name = "Test $id",
        avatarColor = 0,
        avatarId = 0,
        isKids = kids,
    )

    @Test
    fun `no adult PIN means no prompt anywhere`() {
        assertFalse(AdultPinRule.needed(always = true, activeIsKids = true, adultPinHolders = 0))
        assertFalse(AdultPinRule.needed(always = false, activeIsKids = true, adultPinHolders = 0))
    }

    @Test
    fun `kids profile needs an adult PIN to change or add profiles`() {
        assertTrue(AdultPinRule.needed(always = false, activeIsKids = true, adultPinHolders = 1))
        assertTrue(AdultPinRule.needed(always = false, activeIsKids = true, adultPinHolders = 2))
    }

    @Test
    fun `adult profile in Settings is not asked`() {
        assertFalse(AdultPinRule.needed(always = false, activeIsKids = false, adultPinHolders = 1))
    }

    @Test
    fun `profile picker Add tile asks whoever is holding the remote`() {
        assertTrue(AdultPinRule.needed(always = true, activeIsKids = false, adultPinHolders = 1))
    }

    @Test
    fun `active kids profile is found by id`() {
        val profiles = listOf(profile(1), profile(2, kids = true))
        assertTrue(AdultPinRule.activeIsKids(profiles, 2))
        assertFalse(AdultPinRule.activeIsKids(profiles, 1))
        assertFalse(AdultPinRule.activeIsKids(profiles, 99))
    }

    @Test
    fun `first adult that accepts wins and later adults are not tried`() = runBlocking {
        val tried = ArrayList<Long>()
        val result = AdultPinRule.verifyAny(listOf(profile(1), profile(2), profile(3)), "1234") { p, _ ->
            tried += p.id
            if (p.id == 2L) AdultPinOutcome.Accepted else AdultPinOutcome.Wrong
        }
        assertEquals(AdultPinOutcome.Accepted, result)
        assertEquals(listOf(1L, 2L), tried)
    }

    @Test
    fun `no match is wrong`() = runBlocking {
        val result = AdultPinRule.verifyAny(listOf(profile(1), profile(2)), "0000") { _, _ -> AdultPinOutcome.Wrong }
        assertEquals(AdultPinOutcome.Wrong, result)
    }

    @Test
    fun `wrong beats wait when some adults are waiting`() = runBlocking {
        val result = AdultPinRule.verifyAny(listOf(profile(1), profile(2)), "0000") { p, _ ->
            if (p.id == 1L) AdultPinOutcome.Wait(30) else AdultPinOutcome.Wrong
        }
        assertEquals(AdultPinOutcome.Wrong, result)
    }

    @Test
    fun `all adults waiting gives the shortest wait`() = runBlocking {
        val result = AdultPinRule.verifyAny(listOf(profile(1), profile(2)), "0000") { p, _ ->
            AdultPinOutcome.Wait(if (p.id == 1L) 60 else 15)
        }
        assertEquals(AdultPinOutcome.Wait(15), result)
    }

    @Test
    fun `no adults is wrong, never accepted`() = runBlocking {
        val result = AdultPinRule.verifyAny(emptyList(), "1234") { _, _ -> AdultPinOutcome.Accepted }
        assertEquals(AdultPinOutcome.Wrong, result)
    }
}
