package tv.own.owntv.gambit.text

import tv.own.owntv.core.live.ProviderName
import tv.own.owntv.core.live.ProviderTags

/**
 * Core's [ProviderTags.parse] with HTML entities decoded first, as some providers send them in channel
 * and group names ("&#127875;" → 🎃). Gambit used to patch OwnTV's own ProviderTags; upstream moved it
 * into core (3eef6c56), so OwnTV screens import this under the name `ProviderTags` instead.
 */
object GambitProviderTags {
    fun parse(raw: String): ProviderName = ProviderTags.parse(GambitText.decode(raw))
}
