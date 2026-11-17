package tv.own.owntv.gambit.trakt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GambitTraktTokenStoreTest {
    private class MemoryVault(
        var text: String? = null,
        private val failWrites: Boolean = false,
        private val failClears: Boolean = false,
    ) : TraktTokenVault {
        var writes = 0
        override fun read(): String? = text

        override fun write(text: String): Boolean {
            if (failWrites) return false
            this.text = text
            writes++
            return true
        }

        override fun clear(): Boolean {
            if (failClears) return false
            text = null
            return true
        }
    }

    private val tokens = TraktTokens("access.example", "refresh.example", 1_900_000_000L, "public")

    @Test
    fun tokens_round_trip_through_the_codec() {
        assertEquals(tokens, GambitTraktTokenCodec.decode(GambitTraktTokenCodec.encode(tokens)))
    }

    @Test
    fun a_missing_scope_reads_as_public() {
        val text = """{"v":1,"access":"a","refresh":"r","expires":5}"""
        assertEquals("public", GambitTraktTokenCodec.decode(text)!!.scope)
    }

    @Test
    fun unreadable_text_reads_as_nothing() {
        assertNull(GambitTraktTokenCodec.decode("not json"))
        assertNull(GambitTraktTokenCodec.decode("{}"))
        assertNull(GambitTraktTokenCodec.decode("""{"v":2,"access":"a","refresh":"r","expires":5}"""))
        assertNull(GambitTraktTokenCodec.decode("""{"v":1,"access":"","refresh":"r","expires":5}"""))
        assertNull(GambitTraktTokenCodec.decode("""{"v":1,"access":"a","refresh":"","expires":5}"""))
        assertNull(GambitTraktTokenCodec.decode("""{"v":1,"access":"a","refresh":"r"}"""))
    }

    @Test
    fun empty_vault_reads_as_signed_out() = runBlocking {
        assertNull(GambitTraktTokenStore(MemoryVault()).read())
    }

    @Test
    fun write_then_read_returns_the_same_tokens() = runBlocking {
        val store = GambitTraktTokenStore(MemoryVault())
        store.write(tokens)
        assertEquals(tokens, store.read())
    }

    @Test
    fun a_new_write_replaces_the_old_pair() = runBlocking {
        val vault = MemoryVault()
        val store = GambitTraktTokenStore(vault)
        store.write(tokens)
        val refreshed = TraktTokens("access2.example", "refresh2.example", 1_900_100_000L)
        store.write(refreshed)
        assertEquals(refreshed, store.read())
        assertEquals(2, vault.writes)
    }

    @Test
    fun a_saved_sign_in_that_cannot_be_opened_or_read_reads_as_signed_out() = runBlocking {
        assertNull(GambitTraktTokenStore(MemoryVault(text = null)).read()) // key lost: the vault gives null
        assertNull(GambitTraktTokenStore(MemoryVault(text = "garbage")).read())
    }

    @Test
    fun clear_forgets_the_tokens() = runBlocking {
        val store = GambitTraktTokenStore(MemoryVault())
        store.write(tokens)
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun a_failed_write_throws_so_the_client_reports_a_storage_error() {
        val store = GambitTraktTokenStore(MemoryVault(failWrites = true))
        val e = assertThrows(IllegalStateException::class.java) { runBlocking { store.write(tokens) } }
        assertFalse(e.message.orEmpty().contains("access.example"))
        assertFalse(e.message.orEmpty().contains("refresh.example"))
    }

    @Test
    fun a_failed_clear_throws() {
        val store = GambitTraktTokenStore(MemoryVault(text = GambitTraktTokenCodec.encode(tokens), failClears = true))
        assertThrows(IllegalStateException::class.java) { runBlocking { store.clear() } }
    }

    @Test
    fun the_token_text_never_shows_in_toString() {
        val shown = tokens.toString()
        assertFalse(shown.contains("access.example"))
        assertFalse(shown.contains("refresh.example"))
        assertTrue(shown.contains("expiresAtEpochSeconds=1900000000"))
    }
}
