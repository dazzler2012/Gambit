package tv.own.owntv.gambit.addons.model

/** A meta response (`meta`). For series, episodes are in [videos]. */
data class MetaDetail(
    val id: String,
    val type: String,
    val name: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val videos: List<AddonVideo> = emptyList(),
)

/**
 * One video of a meta (an episode for series). [id] is what the stream call takes, e.g. `tt123:1:2`.
 */
data class AddonVideo(
    val id: String,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
)
