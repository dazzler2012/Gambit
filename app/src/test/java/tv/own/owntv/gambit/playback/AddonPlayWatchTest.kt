package tv.own.owntv.gambit.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spotting a stuck addon play, and when its proxy session may close. */
class AddonPlayWatchTest {

    private fun s(at: Long, pos: Long = 0, buffering: Boolean = true, error: Boolean = false, exo: Boolean = false) =
        PlaySample(at, pos, buffering, error, exo)

    private fun first() = AddonPlayWatch(AddonPlayWatch.FIRST_LIMIT_MS, ignoreWhileExo = true)

    @Test
    fun `the first check fires after 20 s of buffering with no progress`() {
        assertTrue(AddonPlayWatch.FIRST_LIMIT_MS == 20_000L)
        val w = first()
        assertFalse(w.update(s(1_000)))
        assertFalse(w.update(s(20_999)))
        assertTrue(w.update(s(21_000)))
    }

    @Test
    fun `progress starts the count again`() {
        val w = first()
        w.update(s(0, pos = 0))
        assertFalse(w.update(s(15_000, pos = 0)))
        assertFalse(w.update(s(16_000, pos = 500)))
        assertFalse(w.update(s(30_000, pos = 500)))
        assertFalse(w.update(s(49_999, pos = 500)))
        assertTrue(w.update(s(50_000, pos = 500)))
    }

    @Test
    fun `a pause is not stuck`() {
        val w = first()
        w.update(s(0))
        assertFalse(w.update(s(25_000, buffering = false)))
        // Buffering again starts a fresh count.
        assertFalse(w.update(s(26_000)))
        assertFalse(w.update(s(45_000)))
        assertTrue(w.update(s(46_000)))
    }

    @Test
    fun `an error the player shows itself is left to the player`() {
        val w = first()
        w.update(s(0))
        assertFalse(w.update(s(30_000, error = true)))
        assertFalse(w.update(s(60_000, error = true)))
    }

    @Test
    fun `the first stage leaves an ExoPlayer hand-over to Core`() {
        val w = first()
        w.update(s(0, exo = true))
        assertFalse(w.update(s(40_000, exo = true)))
    }

    @Test
    fun `the backstop watches ExoPlayer too, for 60 s`() {
        assertTrue(AddonPlayWatch.BACKSTOP_MS == 60_000L)
        val w = AddonPlayWatch(AddonPlayWatch.BACKSTOP_MS, ignoreWhileExo = false)
        assertFalse(w.update(s(0, exo = true)))
        assertFalse(w.update(s(59_999, exo = true)))
        assertTrue(w.update(s(60_000, exo = true)))
    }

    private val video = "http://127.0.0.1:1/s/token/video.mkv"

    @Test
    fun `the session stays open while the player is on the stream`() {
        assertFalse(AddonSessionRule.shouldClose(video, video, null, 10_000))
    }

    @Test
    fun `another address closes the session at once`() {
        assertTrue(AddonSessionRule.shouldClose("http://127.0.0.1:1/s/other/video.mkv", video, null, 10_000))
    }

    @Test
    fun `a short gap with no address keeps the session, a long one closes it`() {
        assertFalse(AddonSessionRule.shouldClose(null, video, 10_000, 10_000))
        assertFalse(AddonSessionRule.shouldClose(null, video, 10_000, 14_999))
        assertTrue(AddonSessionRule.shouldClose(null, video, 10_000, 15_000))
    }
}
