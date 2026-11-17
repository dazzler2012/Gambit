package tv.own.owntv.gambit.trakt

import org.json.JSONObject

/**
 * The saved form of a Trakt sign-in. The text holds both tokens, so it is only ever stored encrypted
 * (see [GambitTraktVault]). Pure: no Android, no I/O. Never put its output in a log or a message.
 */
internal object GambitTraktTokenCodec {
    private const val VERSION = 1

    fun encode(tokens: TraktTokens): String = JSONObject()
        .put("v", VERSION)
        .put("access", tokens.accessToken)
        .put("refresh", tokens.refreshToken)
        .put("expires", tokens.expiresAtEpochSeconds)
        .put("scope", tokens.scope)
        .toString()

    /** The saved tokens, or null when [text] can't be read, is another version, or lacks a token. */
    fun decode(text: String): TraktTokens? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optInt("v") != VERSION) return null
        val access = root.optString("access")
        val refresh = root.optString("refresh")
        if (access.isEmpty() || refresh.isEmpty() || !root.has("expires")) return null
        return TraktTokens(access, refresh, root.optLong("expires"), root.optString("scope").ifEmpty { "public" })
    }
}
