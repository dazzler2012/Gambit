package tv.own.owntv.gambit.stremio

import java.io.File
import okhttp3.EventListener
import okhttp3.Request
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client the app gives Stremio must have nothing attached that could see a request: no
 * interceptors, no network interceptors and no event listener. Copying it (as StremioClient does
 * with newBuilder) keeps it that way.
 */
class StremioHttpClientTest {
    @Test
    fun `the production Stremio client has no interceptors and no event listener`() {
        val plain = StremioClient.plainHttpClient()
        val copied = plain.newBuilder().build()
        listOf(plain, copied).forEach { client ->
            assertTrue(client.interceptors.isEmpty())
            assertTrue(client.networkInterceptors.isEmpty())
            val call = client.newCall(Request.Builder().url("https://link.stremio.example/").build())
            assertSame(EventListener.NONE, client.eventListenerFactory.create(call))
        }
    }

    @Test
    fun `the app wires Stremio to the plain client`() {
        val module = File("src/main/java/tv/own/owntv/gambit/di/GambitAddonModule.kt")
        assertTrue("expected to run from the app module", module.isFile)
        val lines = module.readLines().filter { "StremioClient(" in it }
        assertTrue(lines.joinToString("\n"), lines.isNotEmpty() && lines.all { "StremioClient.plainHttpClient()" in it })
    }
}
