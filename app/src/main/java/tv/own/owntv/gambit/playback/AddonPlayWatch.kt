package tv.own.owntv.gambit.playback

/** One look at the player while an addon stream is on. [atMs] is a monotonic clock. */
data class PlaySample(
    val atMs: Long,
    val positionMs: Long,
    val buffering: Boolean,
    val hasError: Boolean,
    val exoActive: Boolean,
)

/**
 * Spots a play that is stuck: the player keeps buffering, the position never moves and no error is shown,
 * for [limitMs] in a row. Anything else (progress, a pause, an error the player shows itself) starts the
 * count again.
 *
 * Why Gambit needs this: Core's mpv → ExoPlayer hand-over can cancel itself when mpv was hard-reset first
 * (the reset bumps the load generation the hand-over then checks), leaving a spinner with no error. With
 * [ignoreWhileExo], a play ExoPlayer has taken over is left to Core, which has its own checks there.
 *
 * Plain Kotlin; one instance per stage of one play.
 */
class AddonPlayWatch(private val limitMs: Long, private val ignoreWhileExo: Boolean) {
    private var since: Long? = null
    private var lastPosition: Long? = null

    /** True once the play has been stuck for [limitMs]. */
    fun update(sample: PlaySample): Boolean {
        val moved = lastPosition != null && sample.positionMs != lastPosition
        lastPosition = sample.positionMs
        val waiting = sample.buffering && !sample.hasError && !moved && !(ignoreWhileExo && sample.exoActive)
        if (!waiting) {
            since = null
            return false
        }
        val start = since ?: sample.atMs.also { since = it }
        return sample.atMs - start >= limitMs
    }

    companion object {
        /** First stage: mpv (or Core's own hand-over) gets this long before Gambit retries on ExoPlayer. */
        const val FIRST_LIMIT_MS = 20_000L

        /** The ExoPlayer retry gets this long to play or show its own error before Gambit stops it. */
        const val BACKSTOP_MS = 60_000L
    }
}

/** When a play's proxy session may close. */
object AddonSessionRule {
    /** A reset can leave the player with no address for a moment; only a gap this long means it stopped. */
    const val NO_URL_GRACE_MS = 5_000L

    /**
     * Close when the player is on a different address, or has had none for [NO_URL_GRACE_MS]
     * ([noUrlSinceMs] is when it lost it, null while it has one).
     */
    fun shouldClose(current: String?, video: String, noUrlSinceMs: Long?, nowMs: Long): Boolean = when {
        current == null -> noUrlSinceMs != null && nowMs - noUrlSinceMs >= NO_URL_GRACE_MS
        else -> current != video
    }
}
