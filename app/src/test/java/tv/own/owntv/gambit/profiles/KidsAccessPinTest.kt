package tv.own.owntv.gambit.profiles

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.ProfileEntity

/** The adult PIN in front of Kids access (design tests 28–30): strict, any adult with a PIN counts, one visit only. */
class KidsAccessPinTest {

    private fun adult(id: Long) = ProfileEntity(id = id, name = "Adult $id", avatarColor = 0, avatarId = 0, isKids = false)

    /** Adult 1's PIN is 1111, adult 2's is 2222. */
    private val verify: suspend (ProfileEntity, String) -> AdultPinOutcome = { p, pin ->
        if ((p.id == 1L && pin == "1111") || (p.id == 2L && pin == "2222")) AdultPinOutcome.Accepted else AdultPinOutcome.Wrong
    }

    // --- 28. no adult PIN anywhere ------------------------------------------------------------------

    @Test
    fun `with no adult PIN only onNoPin runs and the page stays locked`() {
        var noPin = 0
        var opened = 0
        val session = strictPinSession(emptyList(), verify, { opened++ }, { noPin++ })
        assertNull(session)
        assertEquals(1, noPin)
        assertEquals(0, opened)
    }

    // --- 29. a wrong PIN ---------------------------------------------------------------------------------

    @Test
    fun `a wrong PIN keeps it locked`() = runBlocking {
        var opened = 0
        val session = strictPinSession(listOf(adult(1), adult(2)), verify, { opened++ }, {})!!
        assertEquals(AdultPinOutcome.Wrong, session.check("0000"))
        session.run()
        assertEquals(0, opened)
        assertEquals(0, opened)
    }

    @Test
    fun `waiting out earlier wrong entries keeps it locked`() = runBlocking {
        var opened = 0
        val waiting = strictPinSession(listOf(adult(1)), { _, _ -> AdultPinOutcome.Wait(30) }, { opened++ }, {})!!
        assertEquals(AdultPinOutcome.Wait(30), waiting.check("1111"))
        waiting.run()
        assertEquals(0, opened)
    }

    // --- 30. the right PIN, for this visit only -------------------------------------------------------------

    @Test
    fun `the right PIN of any adult opens it once`() = runBlocking {
        var opened = 0
        val session = strictPinSession(listOf(adult(1), adult(2)), verify, { opened++ }, {})!!
        assertEquals(AdultPinOutcome.Accepted, session.check("2222"))
        session.run()
        session.run()
        assertEquals(1, opened)
    }

    @Test
    fun `the unlock is for one visit and the next open asks again`() = runBlocking {
        var opened = 0
        val first = strictPinSession(listOf(adult(1)), verify, { opened++ }, {})!!
        first.check("1111")
        first.run()
        assertEquals(1, opened)

        val second = strictPinSession(listOf(adult(1)), verify, { opened++ }, {})
        assertNotNull(second)
        second!!.run() // nothing entered yet
        assertEquals(1, opened)
    }

    @Test
    fun `running before a PIN is accepted does nothing`() {
        var opened = 0
        strictPinSession(listOf(adult(1)), verify, { opened++ }, {})!!.run()
        assertEquals(0, opened)
    }
}
