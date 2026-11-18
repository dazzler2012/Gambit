package tv.own.owntv.gambit.trakt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A blank or missing Client ID must never reach TraktConfig (which rejects it) and never throw. */
class GambitTraktBuildTest {
    /** Counts how often a config was asked for, and keeps the ID it was given. */
    private class CountingMaker {
        var calls = 0
        var lastId: String? = null

        fun make(id: String): TraktConfig {
            calls++
            lastId = id
            return TraktConfig(id)
        }
    }

    @Test
    fun missing_value_is_not_configured_and_makes_no_config() {
        val maker = CountingMaker()
        assertNull(GambitTraktBuild.configFrom(null, maker::make))
        assertEquals(0, maker.calls)
    }

    @Test
    fun empty_value_makes_no_config() {
        val maker = CountingMaker()
        assertNull(GambitTraktBuild.configFrom("", maker::make))
        assertEquals(0, maker.calls)
    }

    @Test
    fun whitespace_only_value_makes_no_config() {
        val maker = CountingMaker()
        assertNull(GambitTraktBuild.configFrom("   \t\r\n ", maker::make))
        assertEquals(0, maker.calls)
    }

    @Test
    fun a_real_looking_value_is_trimmed_and_makes_a_config() {
        val maker = CountingMaker()
        val config = GambitTraktBuild.configFrom("  fake-client-id.example \n", maker::make)
        assertNotNull(config)
        assertEquals(1, maker.calls)
        assertEquals("fake-client-id.example", maker.lastId)
        assertEquals("fake-client-id.example", config!!.clientId)
        assertNull(config.clientSecret)
    }

    @Test
    fun a_factory_that_rejects_the_value_means_not_configured_not_a_crash() {
        val config = GambitTraktBuild.configFrom("fake-client-id.example") { throw IllegalArgumentException("rejected") }
        assertNull(config)
    }

    @Test
    fun the_default_factory_keeps_the_out_of_band_redirect_and_no_secret() {
        val config = GambitTraktBuild.configFrom("fake-client-id.example")!!
        assertEquals(TraktConfig.DEVICE_REDIRECT_URI, config.redirectUri)
        assertNull(config.clientSecret)
    }

    @Test
    fun clean_trims_and_drops_blank() {
        assertNull(GambitTraktBuild.clean(null))
        assertNull(GambitTraktBuild.clean(""))
        assertNull(GambitTraktBuild.clean("  "))
        assertEquals("abc", GambitTraktBuild.clean(" abc "))
    }

    @Test
    fun this_build_reports_a_consistent_state_and_never_throws() {
        // The CI build may or may not have the secret; either way these must agree and not throw.
        assertEquals(GambitTraktBuild.isConfigured, GambitTraktBuild.config() != null)
    }

    @Test
    fun a_config_never_shows_the_client_id_in_toString() {
        val config = GambitTraktBuild.configFrom("fake-client-id.example")!!
        assertFalse(config.toString().contains("fake-client-id.example"))
    }
}
