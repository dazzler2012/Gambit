package tv.own.owntv.gambit.addons.data

import java.security.SecureRandom

/**
 * The id of one addon setup: 128 random bits, made fresh every time an addon is installed (added, added
 * again at the same address, or its manifest fetched again). Kids approvals are tied to it, so another
 * configuration, a re-added addon or a changed manifest starts unapproved.
 *
 * It is random and never worked out from the address, so it says nothing about the address or the keys
 * in it, even to someone holding the device's keys. Pure: no Android.
 */
object AddonSetupId {
    private const val BYTES = 16
    private val random = SecureRandom()

    /** A new id: 32 lowercase hex digits. */
    fun mint(): String {
        val bytes = ByteArray(BYTES).also(random::nextBytes)
        val out = StringBuilder(BYTES * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(Character.forDigit(v shr 4, 16)).append(Character.forDigit(v and 0x0F, 16))
        }
        return out.toString()
    }

    /** True for an id [mint] could have made. */
    fun isValid(id: String): Boolean = id.length == BYTES * 2 && id.all { it in '0'..'9' || it in 'a'..'f' }
}
