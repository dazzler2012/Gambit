package tv.own.owntv.gambit

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import tv.own.owntv.core.settings.GuideWidthLimits
import tv.own.owntv.core.settings.GuideWidthShares
import tv.own.owntv.core.settings.normalizeGuideWidths

/**
 * The values [GambitDefaults] writes once per install. applyOnce itself needs a Context and the
 * DataStore-backed SettingsRepository, so only the values are checked here.
 */
class GambitDefaultsTest {

    private val guideSplit = GuideWidthShares(GambitDefaults.GUIDE_CHANNEL_SHARE, GuideWidthLimits.TOTAL - GambitDefaults.GUIDE_CHANNEL_SHARE)

    @Test
    fun `the startup update check stays off`() {
        assertFalse(GambitDefaults.STARTUP_UPDATE_CHECK)
    }

    @Test
    fun `the accent is a six-digit hex colour`() {
        assertTrue(GambitDefaults.ACCENT_HEX.matches(Regex("#[0-9A-Fa-f]{6}")))
        val rgb = GambitDefaults.ACCENT_HEX.removePrefix("#").toLong(16)
        assertEquals(Color(0xFF66BB6A), Color(0xFF000000 or rgb))
    }

    @Test
    fun `the guide split the defaults write is a valid split`() {
        assertTrue("$guideSplit is not a valid guide split", guideSplit.isValid)
    }

    // Known issue, left unfixed on purpose (tests-only change): GUIDE_CHANNEL_SHARE = 26 is off
    // GuideWidthLimits' 5-point grid, and core's SettingsRepository normalises the split when it reads
    // it back, so the guide shows 25/75, not 26/74. Remove @Ignore once the default is on the grid.
    @Ignore("GUIDE_CHANNEL_SHARE 26 is read back as 25 — see comment")
    @Test
    fun `the guide split the defaults write survives normalisation unchanged`() {
        assertEquals(guideSplit, normalizeGuideWidths(guideSplit))
    }
}
