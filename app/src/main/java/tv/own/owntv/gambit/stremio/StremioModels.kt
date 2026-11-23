package tv.own.owntv.gambit.stremio

import tv.own.owntv.gambit.addons.net.AddonUrl

/**
 * A link code from Stremio's link service: the short [code] the TV shows and the [link] page the phone
 * opens (drawn as a QR code). Neither is secret; the code only works until it is approved or expires.
 */
data class StremioLinkCode(val code: String, val link: String)

/** One check of a link code. */
sealed interface StremioLinkStatus {
    /** Not approved on the phone yet, or the code has expired: Stremio answers both the same way. */
    data object Waiting : StremioLinkStatus

    /** Approved. Use [key] for the one import call, then drop it. */
    class Linked(val key: StremioAuthKey) : StremioLinkStatus {
        override fun toString(): String = "Linked(<hidden>)"
    }
}

/**
 * One addon from the user's Stremio account. [url] is an addon address (it may hold debrid keys), so
 * it is never logged or shown: [toString] shows the name and host only. [name] is the manifest name,
 * null when the account copy has none.
 *
 * [official] and [protected] are Stremio's own flags: official marks Stremio's built-in addons
 * (Cinemeta, Local Files, OpenSubtitles and others); protected marks ones the Stremio apps won't let
 * the user remove.
 *
 * [legacy] is true when the address doesn't end in `/manifest.json`: an addon on Stremio's old
 * protocol (OpenSubtitles v1 is one), which Gambit can't talk to.
 */
data class StremioAccountAddon(
    val name: String?,
    val url: AddonUrl,
    val official: Boolean,
    val protected: Boolean,
    val legacy: Boolean = false,
) {
    override fun toString(): String =
        "StremioAccountAddon($name, ${url.host}, official=$official, protected=$protected, legacy=$legacy)"
}

/**
 * The account's addon list in Stremio's order. [unreadable] counts entries dropped because their
 * address isn't a usable http(s) URL.
 */
data class StremioAddonCollection(val addons: List<StremioAccountAddon>, val unreadable: Int)
