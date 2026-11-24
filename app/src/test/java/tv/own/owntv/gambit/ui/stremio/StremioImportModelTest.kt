package tv.own.owntv.gambit.ui.stremio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.FakeAddonServer
import tv.own.owntv.gambit.addons.TestAddons
import tv.own.owntv.gambit.addons.TestAddons.LISTS_BASE
import tv.own.owntv.gambit.addons.TestAddons.META_BASE
import tv.own.owntv.gambit.addons.data.AddonImportReport
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.data.AddonStore
import tv.own.owntv.gambit.addons.data.AddonStoreCodec
import tv.own.owntv.gambit.addons.data.AddonVault
import tv.own.owntv.gambit.addons.net.AddonClient
import tv.own.owntv.gambit.addons.net.AddonUrl
import tv.own.owntv.gambit.stremio.FakeStremio
import tv.own.owntv.gambit.stremio.FakeStremioHost
import tv.own.owntv.gambit.stremio.ImportKind
import tv.own.owntv.gambit.stremio.StremioError
import tv.own.owntv.gambit.stremio.StremioImporter

class StremioImportModelTest {
    private class MemoryVault(var text: String?) : AddonVault {
        override fun hasSaved(): Boolean = text != null
        override fun read(): String? = text
        override fun write(text: String): Boolean { this.text = text; return true }
    }

    private val addons = FakeAddonServer().onFixture("$LISTS_BASE/manifest.json", "manifest_lists.json")
    private val store = AddonStore(AddonRepository(AddonClient(addons.client)), MemoryVault(AddonStoreCodec.encode(listOf(TestAddons.meta))))

    /** Each poll waits here until the test lets it go, so the test can look at the Waiting screen. */
    private var gate = CompletableDeferred<Unit>()

    private fun host() = FakeStremioHost()
        .onJson("GET", "/api/v2/create", """{"result":{"code":"AB12","link":"https://link.stremio.example/AB12"}}""")
        .on("GET", "/api/v2/read", FakeStremioHost.Reply(body = WAITING), FakeStremioHost.Reply(body = LINKED))
        .onJson("POST", "/api/addonCollectionGet", ACCOUNT)

    private fun model(host: FakeStremioHost, scope: CoroutineScope): StremioImportModel {
        val importer = StremioImporter(
            FakeStremio.client(host), store, timeoutMs = 60_000,
            nowMs = { 0L }, pause = { gate.await(); gate = CompletableDeferred() },
        )
        return StremioImportModel(importer, scope, nowMillis = { 1_000L })
    }

    private suspend fun StremioImportModel.await(test: (StremioImportState) -> Boolean): StremioImportState =
        withTimeout(5_000) { state.first(test) }

    @Test
    fun `start shows the code, then the account's addons with new ones ticked`() = runBlocking {
        val model = model(host(), this)
        val seen = mutableListOf<StremioImportState>()
        val watcher = launch(Dispatchers.Unconfined) { model.state.collect { seen += it } }

        model.start()
        val waiting = model.await { it is StremioImportState.Waiting } as StremioImportState.Waiting
        assertEquals("AB12", waiting.code)
        assertEquals("https://link.stremio.example/AB12", waiting.link)
        assertEquals(61_000L, waiting.expiresAtMillis)

        gate.complete(Unit)
        val choosing = model.await { it is StremioImportState.Choosing } as StremioImportState.Choosing
        assertEquals(listOf("AIOMetadata", "Lists", "Cinemeta"), choosing.preview.candidates.map { it.name })
        assertEquals(listOf(ImportKind.ALREADY_ADDED, ImportKind.NEW, ImportKind.STREMIO_OWN), choosing.preview.candidates.map { it.kind })
        assertEquals(setOf(1), choosing.ticked)

        watcher.cancel()
        seen.forEach { assertFalse(it.toString(), it.toString().contains(FakeStremio.KEY)) }
    }

    @Test
    fun `rows that are already added can't be ticked, others toggle`() = runBlocking {
        val model = model(host(), this)
        model.start()
        model.await { it is StremioImportState.Waiting }
        gate.complete(Unit)
        model.await { it is StremioImportState.Choosing }

        model.toggle(0)
        assertEquals(setOf(1), (model.state.value as StremioImportState.Choosing).ticked)
        model.toggle(2)
        assertEquals(setOf(1, 2), (model.state.value as StremioImportState.Choosing).ticked)
        model.toggle(1)
        assertEquals(setOf(2), (model.state.value as StremioImportState.Choosing).ticked)
        model.toggle(9)
        assertEquals(setOf(2), (model.state.value as StremioImportState.Choosing).ticked)
    }

    @Test
    fun `import adds the ticked rows and reports what happened`() = runBlocking {
        val model = model(host(), this)
        model.start()
        model.await { it is StremioImportState.Waiting }
        gate.complete(Unit)
        model.await { it is StremioImportState.Choosing }

        model.import()
        val done = model.await { it is StremioImportState.Done } as StremioImportState.Done

        assertEquals(AddonImportReport(added = 1), done.report)
        assertEquals(1, done.unsupported)
        assertEquals(listOf(TestAddons.meta.url, AddonUrl.parse("$LISTS_BASE/manifest.json")), store.registry.value.all.map { it.url })
    }

    @Test
    fun `cancel while waiting goes back to the start and stops checking`() = runBlocking {
        val host = host()
        val model = model(host, this)
        model.start()
        model.await { it is StremioImportState.Waiting }

        model.cancel()
        assertEquals(StremioImportState.Idle, model.state.value)
        gate.complete(Unit)
        assertEquals(StremioImportState.Idle, model.state.value)
        assertTrue(host.seen.none { it.method == "POST" })
    }

    @Test
    fun `no code from Stremio is a typed failure`() = runBlocking {
        val host = FakeStremioHost().onJson("GET", "/api/v2/create", "", code = 503)
        val model = model(host, this)
        model.start()
        assertEquals(StremioImportState.Failed(StremioError.HttpStatus(503)), model.await { it is StremioImportState.Failed })
    }

    private companion object {
        const val WAITING = """{"error":{"code":101,"message":"Invalid or expired token"}}"""
        val LINKED = """{"result":{"authKey":"${FakeStremio.KEY}"}}"""
        val ACCOUNT = """{"result":{"addons":[
            {"transportUrl":"$META_BASE/manifest.json","manifest":{"name":"AIOMetadata"}},
            {"transportUrl":"$LISTS_BASE/manifest.json","manifest":{"name":"Lists"}},
            {"transportUrl":"https://cinemeta.example/manifest.json","manifest":{"name":"Cinemeta"},"flags":{"official":true,"protected":true}},
            {"transportUrl":"http://127.0.0.1:11470/local-addon/manifest.json","manifest":{"name":"Local Files"},"flags":{"official":true,"protected":true}}
        ]}}"""
    }
}
