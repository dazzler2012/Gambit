package tv.own.owntv.gambit.ui.trakt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** More → Trakt shows only for a profile read as not kids, in a build with a Trakt Client ID. */
class TraktEntryShownTest {
    @Test
    fun `adult profile in a configured build shows Trakt`() = assertTrue(traktEntryShown(isKids = false, configured = true))

    @Test
    fun `kids profile never shows Trakt`() {
        assertFalse(traktEntryShown(isKids = true, configured = true))
        assertFalse(traktEntryShown(isKids = true, configured = false))
    }

    @Test
    fun `an unread, missing or unknown profile fails closed`() {
        assertFalse(traktEntryShown(isKids = null, configured = true))
        assertFalse(traktEntryShown(isKids = null, configured = false))
    }

    @Test
    fun `a build without a Client ID never shows Trakt`() = assertFalse(traktEntryShown(isKids = false, configured = false))
}
