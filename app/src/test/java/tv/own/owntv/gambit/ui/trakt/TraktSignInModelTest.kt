package tv.own.owntv.gambit.ui.trakt

import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.trakt.Canned
import tv.own.owntv.gambit.trakt.Fake
import tv.own.owntv.gambit.trakt.FakeClock
import tv.own.owntv.gambit.trakt.FakeTraktHost
import tv.own.owntv.gambit.trakt.MemoryTokenStore
import tv.own.owntv.gambit.trakt.TraktClient
import tv.own.owntv.gambit.trakt.TraktError
import tv.own.owntv.gambit.trakt.TraktRenewalCheck

/** The sign-in state machine against a fake Trakt host (trakt.fake.test). Nothing leaves the test. */
class TraktSignInModelTest {
    // One thread, so the fake host and clock are only ever touched by one coroutine at a time.
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val host = FakeTraktHost()
    private val store = MemoryTokenStore()
    private val clock = FakeClock()

    /** Each poll waits for one permit when [gated]; otherwise polls run straight through on the fake clock. */
    private val permits = Channel<Unit>(Channel.UNLIMITED)
    private var gated = false

    private val client = TraktClient(
        config = Fake.config,
        tokenStore = store,
        httpClient = OkHttpClient.Builder().addInterceptor(host).build(),
        nowMillis = { clock.nowMillis },
        sleep = { millis ->
            clock.sleep(millis)
            if (gated) permits.receive() else kotlinx.coroutines.yield()
        },
    )

    private fun model(withClient: Boolean = true) = TraktSignInModel(if (withClient) client else null, scope) { clock.nowMillis }

    private fun codeReply() = host.on("POST", "/oauth/device/code", Canned(body = Fake.fixture("device_code.json")))

    private fun polls(vararg replies: Canned) = host.on("POST", "/oauth/device/token", *replies)

    private suspend fun TraktSignInModel.until(done: (TraktSignInState) -> Boolean): TraktSignInState =
        withTimeout(5_000) { state.first(done) }

    @After
    fun stop() {
        scope.cancel()
        executor.shutdownNow()
    }

    private fun errorOf(m: TraktSignInModel) = (m.state.value as TraktSignInState.Failed).error

    @Test
    fun `no client id means not configured and every action does nothing`() = runBlocking {
        val m = model(withClient = false)
        assertEquals(TraktSignInState.NotConfigured, m.state.value)
        m.start()
        m.cancel()
        m.signOut()
        m.clearFailure()
        assertEquals(TraktSignInState.NotConfigured, m.state.value)
        assertTrue(host.seen.isEmpty())
    }

    @Test
    fun `starts signed out when nothing is saved and signed in when tokens are saved`() = runBlocking {
        assertEquals(TraktSignInState.SignedOut, model().until { it != TraktSignInState.Checking })
        store.tokens = tokens()
        assertEquals(TraktSignInState.SignedIn, model().until { it != TraktSignInState.Checking })
    }

    @Test
    fun `an unreadable store is reported as a storage error`() = runBlocking {
        store.failWith = IllegalStateException()
        val m = model()
        m.until { it != TraktSignInState.Checking }
        assertTrue(errorOf(m) is TraktError.Storage)
    }

    @Test
    fun `shows the code and its 10 minute expiry, then signs in once approved`() = runBlocking {
        codeReply()
        polls(Canned(status = 400), Canned(status = 400), Canned(body = Fake.fixture("token_ok.json")))
        val m = model()
        val startedAt = clock.nowMillis
        m.start()

        val waiting = m.until { it is TraktSignInState.Waiting || it == TraktSignInState.SignedIn }
        // Fast fake polling may already be done; then the saved tokens prove the approval.
        if (waiting is TraktSignInState.Waiting) {
            assertEquals(Fake.USER_CODE, waiting.userCode)
            assertEquals("https://trakt.fake.test/activate", waiting.verificationUrl)
            assertEquals(startedAt + 600_000L, waiting.expiresAtMillis)
            assertFalse(waiting.toString().contains(Fake.USER_CODE))
        }
        assertEquals(TraktSignInState.SignedIn, m.until { it == TraktSignInState.SignedIn })
        assertNotNull(store.tokens)
        assertEquals(Fake.NEW_ACCESS, store.tokens!!.accessToken)
    }

    @Test
    fun `waiting state carries the expiry from the code reply`() = runBlocking {
        gated = true
        codeReply()
        polls(Canned(status = 400))
        val m = model()
        val startedAt = clock.nowMillis
        m.start()
        val waiting = m.until { it is TraktSignInState.Waiting } as TraktSignInState.Waiting
        assertEquals(startedAt + 600_000L, waiting.expiresAtMillis)
        assertFalse(waiting.toString().contains(Fake.USER_CODE))
        m.cancel()
    }

    @Test
    fun `polls at the interval Trakt gave`() = runBlocking {
        codeReply()
        polls(Canned(status = 400), Canned(status = 400), Canned(body = Fake.fixture("token_ok.json")))
        val m = model()
        m.start()
        m.until { it == TraktSignInState.SignedIn }
        assertEquals(listOf(5_000L, 5_000L, 5_000L), clock.sleeps)
        assertEquals(3, host.seen("POST", "/oauth/device/token").size)
    }

    @Test
    fun `a code nobody approves ends as expired after 10 minutes`() = runBlocking {
        codeReply()
        polls(Canned(status = 400))
        val m = model()
        m.start()
        m.until { it is TraktSignInState.Failed }
        assertEquals(TraktError.CodeExpired, errorOf(m))
        // 600 s at 5 s per poll: the clock moved past the deadline and polling stopped there.
        assertTrue(clock.sleeps.sum() >= 600_000L)
        assertTrue(clock.sleeps.sum() < 600_000L + 5_000L + 1)
    }

    @Test
    fun `denied on the phone ends as denied`() = runBlocking {
        codeReply()
        polls(Canned(status = 400), Canned(status = 418))
        val m = model()
        m.start()
        m.until { it is TraktSignInState.Failed }
        assertEquals(TraktError.CodeDenied, errorOf(m))
    }

    @Test
    fun `an already used code ends as already used`() = runBlocking {
        codeReply()
        polls(Canned(status = 409))
        val m = model()
        m.start()
        m.until { it is TraktSignInState.Failed }
        assertEquals(TraktError.CodeAlreadyUsed, errorOf(m))
    }

    @Test
    fun `an expired code reply ends as expired`() = runBlocking {
        codeReply()
        polls(Canned(status = 410))
        val m = model()
        m.start()
        m.until { it is TraktSignInState.Failed }
        assertEquals(TraktError.CodeExpired, errorOf(m))
    }

    @Test
    fun `an unknown code ends as invalid`() = runBlocking {
        codeReply()
        polls(Canned(status = 404))
        val m = model()
        m.start()
        m.until { it is TraktSignInState.Failed }
        assertEquals(TraktError.InvalidCode, errorOf(m))
    }

    @Test
    fun `a Trakt that refuses the client id fails before any code is shown`() = runBlocking {
        host.on("POST", "/oauth/device/code", Canned(status = 403))
        val m = model()
        m.start()
        m.until { it is TraktSignInState.Failed }
        assertEquals(TraktError.Forbidden, errorOf(m))
        assertTrue(host.seen("POST", "/oauth/device/token").isEmpty())
    }

    @Test
    fun `no network while asking for a code is a network error`() = runBlocking {
        host.on("POST", "/oauth/device/code", Canned(failure = IOException("offline")))
        val m = model()
        m.start()
        m.until { it is TraktSignInState.Failed }
        assertTrue(errorOf(m) is TraktError.Network)
    }

    @Test
    fun `back stops the poll and returns to signed out`() = runBlocking {
        gated = true
        codeReply()
        polls(Canned(status = 400))
        val m = model()
        m.start()
        m.until { it is TraktSignInState.Waiting }
        permits.send(Unit) // one poll goes out
        withTimeout(5_000) { while (host.seen("POST", "/oauth/device/token").isEmpty()) kotlinx.coroutines.yield() }

        m.cancel()
        scope.coroutineContext.job.children.toList().joinAll()
        val pollsAtCancel = host.seen("POST", "/oauth/device/token").size
        repeat(3) { permits.send(Unit) } // nothing is waiting for these any more

        assertEquals(TraktSignInState.SignedOut, m.state.value)
        assertEquals(pollsAtCancel, host.seen("POST", "/oauth/device/token").size)
        assertNull(store.tokens)
    }

    @Test
    fun `back does not touch a failure or a finished sign in`() = runBlocking {
        codeReply()
        polls(Canned(status = 418))
        val m = model()
        m.start()
        m.until { it is TraktSignInState.Failed }
        m.cancel()
        assertEquals(TraktError.CodeDenied, errorOf(m))
    }

    @Test
    fun `try again after a failure starts a fresh sign in`() = runBlocking {
        codeReply()
        polls(Canned(status = 418), Canned(body = Fake.fixture("token_ok.json")))
        val m = model()
        m.start()
        m.until { it is TraktSignInState.Failed }
        m.clearFailure()
        assertEquals(TraktSignInState.SignedOut, m.state.value)
        m.start()
        m.until { it == TraktSignInState.SignedIn }
        assertNotNull(store.tokens)
    }

    @Test
    fun `sign out forgets the tokens and stops a running sign in`() = runBlocking {
        store.tokens = tokens()
        val m = model()
        m.until { it == TraktSignInState.SignedIn }
        m.signOut()
        m.until { it == TraktSignInState.SignedOut }
        assertEquals(null, store.tokens)
    }

    @Test
    fun `a failed sign out is a storage error`() = runBlocking {
        store.tokens = tokens()
        val m = model()
        m.until { it == TraktSignInState.SignedIn }
        store.failWith = IllegalStateException()
        m.signOut()
        m.until { it is TraktSignInState.Failed }
        assertTrue(errorOf(m) is TraktError.Storage)
    }

    @Test
    fun `opening the page again notices a sign in cleared in the background`() = runBlocking {
        store.tokens = tokens()
        val m = model()
        m.until { it == TraktSignInState.SignedIn }
        store.tokens = null
        m.recheck()
        assertEquals(TraktSignInState.SignedOut, m.until { it == TraktSignInState.SignedOut })
    }

    @Test
    fun `a renewal check that Trakt refuses shows signed out and keeps the result`() = runBlocking {
        store.tokens = tokens()
        host.on("POST", "/oauth/token", Canned(status = 400))
        val m = TraktSignInModel(client, scope, TraktRenewalCheck(client, store) { clock.nowMillis }) { clock.nowMillis }
        assertTrue(m.canCheckRenewal)
        m.until { it == TraktSignInState.SignedIn }

        m.checkRenewal()

        val done = withTimeout(5_000) { m.renewal.first { it is TraktRenewalState.Done } } as TraktRenewalState.Done
        assertEquals(TraktError.SignInExpired, done.result.forced.error)
        assertEquals(TraktSignInState.SignedOut, m.until { it == TraktSignInState.SignedOut })
        assertNull(store.tokens)
    }

    @Test
    fun `the renewal check does nothing unless signed in or without a check`() = runBlocking {
        val m = TraktSignInModel(client, scope, TraktRenewalCheck(client, store) { clock.nowMillis }) { clock.nowMillis }
        m.until { it == TraktSignInState.SignedOut }
        m.checkRenewal()
        assertEquals(TraktRenewalState.Idle, m.renewal.value)
        assertFalse(model().canCheckRenewal)
        assertTrue(host.seen.isEmpty())
    }

    private fun tokens() = tv.own.owntv.gambit.trakt.TraktTokens(Fake.ACCESS, Fake.REFRESH, 1_700_000_000L + 86_400L)
}
