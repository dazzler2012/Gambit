package tv.own.owntv.gambit.addons.model

/** One item of a catalog response (`metas[]`). Only [id] and [type] are guaranteed. */
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String? = null,
    val poster: String? = null,
    val posterShape: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
)
