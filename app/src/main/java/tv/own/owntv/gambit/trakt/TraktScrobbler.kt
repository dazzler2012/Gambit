package tv.own.owntv.gambit.trakt

import org.json.JSONObject

/**
 * Tells Trakt what is playing. Call [start] when playback starts or resumes, [pause] when it pauses,
 * and [stop] when it stops or ends. Trakt marks the item watched on [stop] at 80% or more and saves
 * the position as playback progress below that.
 *
 * [progress] is a percentage, 0 to 100; values outside are clamped. Trakt ignores progress under 1%
 * with a 422 ([TraktError.Unprocessable]). A [stop] for an item just scrobbled gets a 409
 * ([TraktError.Conflict], with when it can be scrobbled again).
 */
class TraktScrobbler internal constructor(
    private val http: TraktHttp,
    private val session: TraktSession,
) {
    suspend fun start(item: TraktScrobbleItem, progress: Double): TraktResult<TraktScrobbleResult> = send("start", item, progress)

    suspend fun pause(item: TraktScrobbleItem, progress: Double): TraktResult<TraktScrobbleResult> = send("pause", item, progress)

    suspend fun stop(item: TraktScrobbleItem, progress: Double): TraktResult<TraktScrobbleResult> = send("stop", item, progress)

    private suspend fun send(action: String, item: TraktScrobbleItem, progress: Double): TraktResult<TraktScrobbleResult> {
        val body = item.toJson().put("progress", progress.coerceIn(0.0, 100.0))
        return session.authorized { token ->
            http.send("POST", "scrobble/$action", body, accessToken = token).flatMap { reply ->
                parsing { parseObject(reply.body).toScrobbleResult() }
            }
        }
    }
}

internal fun TraktScrobbleItem.toJson(): JSONObject = when (this) {
    is TraktScrobbleItem.Movie -> JSONObject().put(
        "movie",
        JSONObject().apply {
            title?.let { put("title", it) }
            year?.let { put("year", it) }
            put("ids", ids.toJson())
        },
    )
    is TraktScrobbleItem.Episode -> JSONObject().put("episode", JSONObject().put("ids", ids.toJson()))
    is TraktScrobbleItem.EpisodeOfShow -> JSONObject()
        .put("show", JSONObject().put("ids", showIds.toJson()))
        .put("episode", JSONObject().put("season", season).put("number", number))
}

private fun JSONObject.toScrobbleResult(): TraktScrobbleResult {
    val show = objectOrNull("show")?.toShow()
    val item = when {
        objectOrNull("movie") != null -> TraktMediaItem.Movie(requireObject("movie").toMovie())
        objectOrNull("episode") != null -> TraktMediaItem.Episode(requireObject("episode").toEpisode(show))
        else -> null
    }
    return TraktScrobbleResult(
        id = longOrNull("id"),
        action = requireString("action"),
        progress = doubleOrNull("progress") ?: throw TraktJsonException("missing 'progress'"),
        item = item,
    )
}
