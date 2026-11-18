package tv.own.owntv.gambit.ui.trakt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.R
import tv.own.owntv.gambit.trakt.TraktError

class TraktLabelsTest {
    /** One of every TraktError type. */
    private val all: List<TraktError> = listOf(
        TraktError.Timeout, TraktError.Network("X"), TraktError.Unauthorized, TraktError.Forbidden, TraktError.NotFound,
        TraktError.Conflict(null, null), TraktError.Unprocessable, TraktError.RateLimited(null), TraktError.Server(500),
        TraktError.Http(418), TraktError.Malformed("x"), TraktError.NotSignedIn, TraktError.SignInExpired, TraktError.Storage("X"),
        TraktError.CodeExpired, TraktError.CodeDenied, TraktError.CodeAlreadyUsed, TraktError.InvalidCode,
    )

    @Test
    fun every_error_has_a_text() {
        all.forEach { assertTrue("$it has no text", it.messageRes() != 0) }
    }

    @Test
    fun the_sign_in_outcomes_each_have_their_own_text() {
        assertEquals(R.string.gambit_trakt_err_expired, TraktError.CodeExpired.messageRes())
        assertEquals(R.string.gambit_trakt_err_denied, TraktError.CodeDenied.messageRes())
        assertEquals(R.string.gambit_trakt_err_used, TraktError.CodeAlreadyUsed.messageRes())
        assertEquals(R.string.gambit_trakt_err_invalid, TraktError.InvalidCode.messageRes())
        assertEquals(4, setOf(TraktError.CodeExpired, TraktError.CodeDenied, TraktError.CodeAlreadyUsed, TraktError.InvalidCode).map { it.messageRes() }.toSet().size)
    }

    @Test
    fun network_problems_share_one_text() {
        assertEquals(R.string.gambit_trakt_err_network, TraktError.Timeout.messageRes())
        assertEquals(R.string.gambit_trakt_err_network, TraktError.Network("UnknownHostException").messageRes())
        assertNotEquals(R.string.gambit_trakt_err_network, TraktError.Forbidden.messageRes())
    }

    @Test
    fun a_rejected_client_and_a_busy_trakt_read_differently() {
        assertEquals(R.string.gambit_trakt_err_refused, TraktError.Forbidden.messageRes())
        assertEquals(R.string.gambit_trakt_err_busy, TraktError.Server(503).messageRes())
        assertEquals(R.string.gambit_trakt_err_busy, TraktError.RateLimited(30).messageRes())
    }
}
