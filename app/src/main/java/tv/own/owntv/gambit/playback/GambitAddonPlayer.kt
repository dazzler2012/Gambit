package tv.own.owntv.gambit.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.gambit.addons.model.AddonStream
import tv.own.owntv.player.ExternalSubtitleSource
import tv.own.owntv.player.OwnTVPlayer

/**
 * What to play: one addon stream, and how the player should name it. [videoId] and [metaId] are addon
 * ids (not secret); the stream's address and headers stay inside [stream] and only reach [GambitStreamProxy].
 */
data class AddonPlayRequest(
    val stream: AddonStream,
    val addonName: String,
    val type: String,
    val videoId: String,
    val metaId: String,
    val title: String,
    val year: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
)

enum class AddonPlayResult { STARTED, HLS, NOT_PLAYABLE }

/** The HUD names and keys an addon item gets; no address in any of them. */
object AddonPlayMeta {
    private const val KEY_PREFIX = "gambit-addon"
    private const val SERIES = "series"

    /** Engine-pin and progress identity: never the URL, so Core keys nothing on it. */
    fun contentKey(type: String, videoId: String): String = "$KEY_PREFIX:$type:$videoId"

    /** Remembered audio and subtitle tracks: per show for an episode, per item otherwise. */
    fun trackKey(type: String, metaId: String, videoId: String): String =
        if (type == SERIES) "$KEY_PREFIX:$SERIES:$metaId" else contentKey(type, videoId)
}

/**
 * Plays an addon stream in Gambit's normal player through [GambitStreamProxy]: the player gets a
 * loopback address only. The video and the addon's subtitles share one proxy session, closed as soon as
 * the player moves to anything else or stops. HLS is refused before playing.
 *
 * [labelSubtitle] words each subtitle's track label (index, language, addon name); labels must be unique.
 */
class GambitAddonPlayer(
    private val player: OwnTVPlayer,
    private val proxy: GambitStreamProxy,
    http: OkHttpClient,
    private val labelSubtitle: (index: Int, lang: String?, addonName: String) -> String,
) {
    private val hlsProbe = HlsProbe(http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: GambitStreamProxy.Session? = null
    private var watch: Job? = null
    private val _failures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when Gambit had to stop a play that stayed stuck on both engines. */
    val failures: SharedFlow<Unit> = _failures.asSharedFlow()

    /** Must be called on the main thread (the player's own rule for play()). */
    suspend fun play(request: AddonPlayRequest): AddonPlayResult {
        val stream = request.stream
        val url = stream.url
        if (url == null || !stream.isPlayable) return AddonPlayResult.NOT_PLAYABLE
        val headers = stream.behaviorHints.requestHeaders
        if (StreamKind.isHlsUrl(url) || withContext(Dispatchers.IO) { hlsProbe.isHls(url, headers) }) return AddonPlayResult.HLS

        stopSession()
        val opened = proxy.openSession()
        session = opened
        val ext = StreamKind.extension(stream.behaviorHints.filename, url)
        val video = opened.add(url, headers, ext?.let { "$VIDEO_NAME.$it" } ?: VIDEO_NAME)
        val subs = stream.subtitles.mapIndexed { i, sub ->
            val subExt = StreamKind.extension(null, sub.url) ?: SUB_EXT
            OwnTVPlayer.ExternalSub(
                path = opened.add(sub.url, emptyMap(), "$SUB_NAME$i.$subExt"),
                title = labelSubtitle(i, sub.lang, request.addonName),
                lang = sub.lang,
                source = ExternalSubtitleSource.LOCAL,
            )
        }
        start(request, video, engine = null)
        watch(opened, request, video, subs)
        return AddonPlayResult.STARTED
    }

    /** Hands [video] to the player; [engine] null follows the user's Movies & Series player setting. */
    private fun start(request: AddonPlayRequest, video: String, engine: EnginePreference?) {
        val episode = request.season != null && request.episode != null
        player.play(
            url = video,
            title = if (episode) request.episodeTitle else request.title,
            subtitle = if (episode) request.title else null,
            year = request.year,
            isLive = false,
            vodEngineOverride = engine,
            contentKey = AddonPlayMeta.contentKey(request.type, request.videoId),
            seasonNumber = request.season,
            episodeNumber = request.episode,
            trackKey = AddonPlayMeta.trackKey(request.type, request.metaId, request.videoId),
        )
    }

    /**
     * Keeps the session open while the player is on [video] (a short gap during a player reset is
     * allowed) and closes it once the player is on anything else or stopped. Lists the subtitles once the
     * file has loaded. Watches for a stuck play: after [AddonPlayWatch.FIRST_LIMIT_MS] it plays the same
     * address again on ExoPlayer only (Core then ends a failure with its own error screen); if that retry
     * is stuck too for [AddonPlayWatch.BACKSTOP_MS], the player is stopped and [failures] is told.
     */
    private fun watch(opened: GambitStreamProxy.Session, request: AddonPlayRequest, video: String, subs: List<OwnTVPlayer.ExternalSub>) {
        watch?.cancel()
        watch = scope.launch {
            // play() sets the address at once; allow a moment in case a queued load replaces it later.
            val started = withTimeoutOrNull(START_WAIT_MS) {
                while (player.currentMediaUrl != video) delay(POLL_MS)
                true
            } ?: false
            if (!started) return@launch close(opened)
            val listSubs = {
                if (subs.isNotEmpty()) {
                    launch {
                        // Set only after this load's own FILE_LOADED, and cleared by every load.
                        val loaded = withTimeoutOrNull(LOAD_WAIT_MS) { player.playbackStarted.first { it } } != null
                        if (loaded && player.currentMediaUrl == video) player.restoreExternalSubtitles(subs)
                    }
                }
            }
            listSubs()
            var retried = false
            var stuck = AddonPlayWatch(AddonPlayWatch.FIRST_LIMIT_MS, ignoreWhileExo = true)
            var noUrlSince: Long? = null
            while (true) {
                delay(POLL_MS)
                val now = SystemClock.elapsedRealtime()
                val current = player.currentMediaUrl
                noUrlSince = if (current == null) noUrlSince ?: now else null
                if (AddonSessionRule.shouldClose(current, video, noUrlSince, now)) break
                if (current != video) continue
                val sample = PlaySample(
                    atMs = now,
                    positionMs = player.position.value,
                    buffering = player.buffering.value,
                    hasError = player.error.value != null,
                    exoActive = player.exoActiveState.value,
                )
                if (!stuck.update(sample)) continue
                if (!retried) {
                    Log.w(TAG, "addon play stuck; retrying on ExoPlayer only")
                    retried = true
                    stuck = AddonPlayWatch(AddonPlayWatch.BACKSTOP_MS, ignoreWhileExo = false)
                    start(request, video, EnginePreference.EXO_ONLY)
                    listSubs()
                } else {
                    Log.w(TAG, "addon play still stuck on ExoPlayer; stopping")
                    player.stop()
                    _failures.tryEmit(Unit)
                    break
                }
            }
            close(opened)
        }
    }

    private fun close(opened: GambitStreamProxy.Session) {
        opened.close()
        if (session === opened) session = null
    }

    private fun stopSession() {
        watch?.cancel()
        watch = null
        session?.close()
        session = null
    }

    companion object {
        private const val TAG = "GambitAddonPlayer"
        private const val VIDEO_NAME = "video"
        private const val SUB_NAME = "sub"
        private const val SUB_EXT = "srt"
        private const val POLL_MS = 1_000L
        private const val START_WAIT_MS = 10_000L
        private const val LOAD_WAIT_MS = 60_000L
    }
}
