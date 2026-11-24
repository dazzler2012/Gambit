package tv.own.owntv.gambit.stremio

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.FakeAddonServer
import tv.own.owntv.gambit.addons.TestAddons
import tv.own.owntv.gambit.addons.TestAddons.LISTS_BASE
import tv.own.owntv.gambit.addons.TestAddons.META_BASE
import tv.own.owntv.gambit.addons.TestAddons.STREAMS_B_BASE
import tv.own.owntv.gambit.addons.data.AddonImportFailReason
import tv.own.owntv.gambit.addons.data.AddonImportFailure
import tv.own.owntv.gambit.addons.data.AddonImportReport
import tv.own.owntv.gambit.addons.data.AddonImportRequest
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.data.AddonStore
import tv.own.owntv.gambit.addons.data.AddonStoreCodec
import tv.own.owntv.gambit.addons.data.AddonVault
import tv.own.owntv.gambit.addons.data.InstalledAddon
import tv.own.owntv.gambit.addons.data.KidsAccessVault
import tv.own.owntv.gambit.addons.data.KidsAddonAccess
import tv.own.owntv.gambit.addons.net.AddonClient
import tv.own.owntv.gambit.addons.net.AddonUrl
import tv.own.owntv.gambit.ui.discovery.AddonAccess
import tv.own.owntv.gambit.ui.discovery.registryFrom

class StremioImportTest {
    private class MemoryVault(var text: String? = null) : AddonVault {
        var writes = 0
        override fun hasSaved(): Boolean = text != null
        override fun read(): String? = text
        override fun write(text: String): Boolean {
            this.text = text; writes++
            return true
        }
    }

    private class MemoryKidsVault(var text: String? = null) : KidsAccessVault {
        override fun hasSaved(): Boolean = text != null
        override fun read(): String? = text
        override fun write(text: String): Boolean { this.text = text; return true }
        override fun remove(): Boolean { text = null; return true }
    }

    private val addons = FakeAddonServer()
        .onFixture("$LISTS_BASE/manifest.json", "manifest_lists.json")
        .onFixture("$STREAMS_B_BASE/manifest.json", "manifest_streams.json")
        .onFixture("$OTHER_META_BASE/manifest.json", "manifest_meta.json")
    private val repo = AddonRepository(AddonClient(addons.client))
    private val kids = KidsAddonAccess(MemoryKidsVault(), Dispatchers.Unconfined)

    private fun store(vararg start: InstalledAddon): Pair<AddonStore, MemoryVault> {
        val vault = MemoryVault(if (start.isEmpty()) null else AddonStoreCodec.encode(start.toList()))
        return AddonStore(repo, vault) to vault
    }

    private fun url(base: String) = AddonUrl.parse("$base/manifest.json")!!

    private fun requests(vararg urls: AddonUrl) = urls.map { AddonImportRequest(it, null) }

    private fun account(name: String, base: String, official: Boolean = false, legacy: Boolean = false) =
        StremioAccountAddon(name, if (legacy) AddonUrl.parse(base)!! else url(base), official, official, legacy)

    /** What Discovery shows a kids profile, worked out the way DiscoveryViewModel does. */
    private fun kidsDiscovery(registry: AddonRegistry, profileId: Long): List<String> =
        AddonAccess.Kids(kids.approved(profileId), profileId).registryFrom(registry).browseCatalogs().map { it.key }

    // --- preview ---

    @Test
    fun `preview ticks new addons, offers Stremio's own unticked, greys out existing ones and leaves out unusable ones`() {
        val collection = StremioAddonCollection(
            listOf(
                account("AIOMetadata", META_BASE),
                account("Lists", LISTS_BASE),
                account("Cinemeta", "https://cinemeta.example", official = true),
                account("Local Files", "http://127.0.0.1:11470/local-addon", official = true),
                account("OpenSubtitles", "https://opensubs.example/stremio/v1", official = true, legacy = true),
                account("Lists again", LISTS_BASE),
            ),
            unreadable = 1,
        )
        val preview = StremioImportRules.preview(collection, listOf(TestAddons.meta))

        assertEquals(listOf("AIOMetadata", "Lists", "Cinemeta"), preview.candidates.map { it.name })
        assertEquals(listOf(ImportKind.ALREADY_ADDED, ImportKind.NEW, ImportKind.STREMIO_OWN), preview.candidates.map { it.kind })
        assertEquals(listOf(false, true, false), preview.candidates.map { it.tickedByDefault })
        assertEquals(listOf(false, true, true), preview.candidates.map { it.selectable })
        assertEquals(2, preview.unsupported)
        assertEquals(1, preview.unreadable)
    }

    @Test
    fun `addons served from the device itself are unsupported`() {
        listOf("http://localhost:11470/a", "http://127.0.0.1:11470/a", "http://[::1]:11470/a").forEach {
            assertTrue(it, StremioImportRules.isUnsupported(account("x", it)))
        }
        assertFalse(StremioImportRules.isUnsupported(account("x", "https://addon.example")))
    }

    @Test
    fun `old-protocol addresses are marked legacy when the list is read`() {
        val body = """{"result":{"addons":[
            {"transportUrl":"https://opensubs.example/stremio/v1","manifest":{"name":"Old"}},
            {"transportUrl":"https://new.example/cfg/manifest.json?x=1","manifest":{"name":"New"}}
        ]}}"""
        val addons = (StremioJson.addonCollection(body) as StremioResult.Ok).value.addons
        assertEquals(listOf(true, false), addons.map { it.legacy })
    }

    // --- importAll ---

    @Test
    fun `import adds after the existing addons in Stremio order and never touches one already there`() = runBlocking {
        val streamsA = TestAddons.streamsA.copy(enabled = false)
        val (store, vault) = store(TestAddons.meta, streamsA)

        val report = store.importAll(requests(url(STREAMS_B_BASE), streamsA.url, url(LISTS_BASE)))

        assertEquals(AddonImportReport(added = 2, alreadyAdded = 1), report)
        assertEquals(listOf(TestAddons.meta.url, streamsA.url, url(STREAMS_B_BASE), url(LISTS_BASE)), store.registry.value.all.map { it.url })
        assertFalse("the existing addon keeps its off switch", store.registry.value.all[1].enabled)
        assertFalse(addons.requested.any { it.startsWith(TestAddons.STREAMS_A_BASE) })
        assertEquals(1, vault.writes)
    }

    @Test
    fun `an addon whose manifest can't be read is counted and not added`() = runBlocking {
        val (store, vault) = store(TestAddons.meta)
        val report = store.importAll(listOf(AddonImportRequest(url("https://missing.example/cfg"), "Missing")))
        assertEquals(AddonImportReport(failures = listOf(AddonImportFailure("Missing", AddonImportFailReason.Refused(404)))), report)
        assertEquals(listOf(TestAddons.meta.url), store.registry.value.all.map { it.url })
        assertEquals(0, vault.writes)
    }

    @Test
    fun `each addon that can't be loaded is reported by name and reason, never by address`() = runBlocking {
        addons.on("https://down.example/FAKEKEY-1/manifest.json", FakeAddonServer.Reply.Fail(IOException("down")))
        addons.on("https://slow.example/FAKEKEY-2/manifest.json", FakeAddonServer.Reply.Fail(SocketTimeoutException("slow")))
        addons.onJson("https://refuses.example/FAKEKEY-3/manifest.json", "{}", code = 403)
        addons.onJson("https://notaddon.example/FAKEKEY-4/manifest.json", """{"hello":"world"}""")
        addons.onJson("https://html.example/FAKEKEY-5/manifest.json", "<html>")
        val (store, vault) = store(TestAddons.meta)

        val report = store.importAll(
            listOf(
                AddonImportRequest(url("https://down.example/FAKEKEY-1"), "Down"),
                AddonImportRequest(url("https://slow.example/FAKEKEY-2"), "Slow"),
                AddonImportRequest(url("https://refuses.example/FAKEKEY-3"), "Refuses"),
                AddonImportRequest(url("https://notaddon.example/FAKEKEY-4"), "Not an addon"),
                AddonImportRequest(url("https://html.example/FAKEKEY-5"), null),
            ),
        )

        assertEquals(
            listOf(
                AddonImportFailure("Down", AddonImportFailReason.Unreachable),
                AddonImportFailure("Slow", AddonImportFailReason.Unreachable),
                AddonImportFailure("Refuses", AddonImportFailReason.Refused(403)),
                AddonImportFailure("Not an addon", AddonImportFailReason.NotAnAddon),
                AddonImportFailure(null, AddonImportFailReason.NotAnAddon),
            ),
            report.failures,
        )
        assertEquals(5, report.failed)
        assertEquals(0, report.added)
        assertEquals(0, vault.writes)
        assertFalse(report.toString().contains("FAKEKEY"))
        assertFalse(report.toString().contains(".example"))
    }

    @Test
    fun `the importer reports a failure under the name Stremio gave the addon`() = runBlocking {
        val (store, _) = store(TestAddons.meta)
        val importer = StremioImporter(FakeStremio.client(FakeStremioHost()), store)

        val report = importer.import(listOf(ImportCandidate("My Lists", url("https://missing.example/FAKEKEY"), ImportKind.NEW)))

        assertEquals(listOf(AddonImportFailure("My Lists", AddonImportFailReason.Refused(404))), report.failures)
    }

    // --- the same addon with | or %7C ---

    private val pipeRaw = AddonUrl.parse("https://torrentio.example/sort=size|debrid=FAKEKEY/manifest.json")!!
    private val pipeEscaped = AddonUrl.parse("https://torrentio.example/sort=size%7Cdebrid=FAKEKEY/manifest.json")!!

    @Test
    fun `an addon added with raw pipes is already added when Stremio has it escaped`() = runBlocking {
        val installed = InstalledAddon(pipeRaw, TestAddons.manifest("manifest_streams.json"), setupId = TestAddons.setupId("pipe"))
        val (store, vault) = store(TestAddons.meta, installed)

        val report = store.importAll(requests(pipeEscaped))

        assertEquals(AddonImportReport(alreadyAdded = 1), report)
        assertEquals(2, store.registry.value.all.size)
        assertTrue(addons.requested.isEmpty())
        assertEquals(0, vault.writes)
    }

    @Test
    fun `the preview greys out the escaped form of an addon added with raw pipes`() {
        val installed = InstalledAddon(pipeRaw, TestAddons.manifest("manifest_streams.json"), setupId = TestAddons.setupId("pipe"))
        val collection = StremioAddonCollection(listOf(StremioAccountAddon("Torrent", pipeEscaped, false, false)), 0)
        val preview = StremioImportRules.preview(collection, listOf(installed))
        assertEquals(listOf(ImportKind.ALREADY_ADDED), preview.candidates.map { it.kind })
    }

    @Test
    fun `both forms in one import add the addon once`() = runBlocking {
        addons.onFixture(pipeEscaped.canonical, "manifest_streams.json")
        val (store, _) = store(TestAddons.meta)

        val report = store.importAll(requests(pipeRaw, pipeEscaped))

        assertEquals(AddonImportReport(added = 1), report)
        assertEquals(1, addons.requested.size)
        assertEquals(2, store.registry.value.all.size)
    }

    // --- kids profiles ---

    /** The key of the movie "popular" catalogue of the installed addon at [base]. */
    private fun moviePopular(store: AddonStore, base: String): String =
        store.registry.value.browseCatalogs("movie").first { it.addon.url == url(base) && it.catalog.id == "popular" }.key

    @Test
    fun `import leaves a kids profile's approvals and Discovery unchanged`() = runBlocking {
        val (store, _) = store(TestAddons.meta)
        val popular = moviePopular(store, META_BASE)
        val approved = setOf(popular)
        kids.set(KID, approved)
        val discoveryBefore = kidsDiscovery(store.registry.value, KID)
        val importer = StremioImporter(FakeStremio.client(FakeStremioHost()), store)

        val report = importer.import(listOf(ImportCandidate("Lists", url(LISTS_BASE), ImportKind.NEW)))

        assertEquals(1, report.added)
        assertTrue(store.registry.value.all.any { it.url == url(LISTS_BASE) })
        assertEquals(approved, kids.approved(KID))
        assertEquals(discoveryBefore, kidsDiscovery(store.registry.value, KID))
        assertEquals(listOf(popular), kidsDiscovery(store.registry.value, KID))
    }

    @Test
    fun `another configuration of an approved addon is imported but stays unapproved on a kids profile`() = runBlocking {
        val (store, _) = store(TestAddons.meta)
        val popular = moviePopular(store, META_BASE)
        kids.set(KID, setOf(popular))
        val discoveryBefore = kidsDiscovery(store.registry.value, KID)

        // Same manifest id and host as the installed metadata addon, but another configuration.
        val report = store.importAll(requests(url(OTHER_META_BASE)))

        assertEquals(AddonImportReport(added = 1), report)
        assertEquals(listOf(TestAddons.meta.url, url(OTHER_META_BASE)), store.registry.value.all.map { it.url })
        assertNotEquals(popular, moviePopular(store, OTHER_META_BASE))
        assertEquals(setOf(popular), kids.approved(KID))
        assertEquals(discoveryBefore, kidsDiscovery(store.registry.value, KID))
    }

    @Test
    fun `an approval left over from a removed addon can't bring it back on a kids profile`() = runBlocking {
        val (store, _) = store(TestAddons.meta, TestAddons.lists)
        val watchlist = store.registry.value.browseCatalogs().first { it.catalog.id == "list.watchlist" }.key
        kids.set(KID, setOf(watchlist))
        store.remove(url(LISTS_BASE)) // no watcher here, so the approval is left behind
        val importer = StremioImporter(FakeStremio.client(FakeStremioHost()), store)

        val report = importer.import(listOf(ImportCandidate("Lists", url(LISTS_BASE), ImportKind.NEW)))

        assertEquals(1, report.added)
        assertEquals(emptyList<String>(), kidsDiscovery(store.registry.value, KID))
        assertEquals(setOf(watchlist), kids.approved(KID))
    }

    @Test
    fun `rows that can't be ticked are never imported`() = runBlocking {
        val (store, _) = store(TestAddons.meta)
        val importer = StremioImporter(FakeStremio.client(FakeStremioHost()), store)
        val report = importer.import(listOf(ImportCandidate("Lists", url(LISTS_BASE), ImportKind.ALREADY_ADDED)))
        assertEquals(AddonImportReport(), report)
        assertTrue(addons.requested.isEmpty())
    }

    // --- waiting for the phone ---

    private var now = 0L
    private val pauses = mutableListOf<Long>()

    private fun importer(host: FakeStremioHost, store: AddonStore, timeoutMs: Long = StremioImporter.TIMEOUT_MS) =
        StremioImporter(FakeStremio.client(host), store, timeoutMs = timeoutMs, nowMs = { now }, pause = { now += it; pauses += it })

    @Test
    fun `waits for the phone, then reads the account once and returns the preview`() = runBlocking {
        val host = FakeStremioHost()
            .on(
                "GET", "/api/v2/read",
                FakeStremioHost.Reply(body = WAITING),
                FakeStremioHost.Reply(body = WAITING),
                FakeStremioHost.Reply(failure = IOException("wifi blip")),
                FakeStremioHost.Reply(body = LINKED),
            )
            .onJson("POST", "/api/addonCollectionGet", ACCOUNT_LISTS)
        val (store, _) = store(TestAddons.meta)

        val outcome = importer(host, store).awaitAccountAddons(CODE)

        val preview = (outcome as StremioLinkOutcome.Ready).preview
        assertEquals(listOf("Lists"), preview.candidates.map { it.name })
        assertEquals(listOf(5_000L, 5_000L, 5_000L), pauses)
        assertEquals(1, host.seen.count { it.method == "POST" })
        assertEquals(1, host.seen.count { it.body.contains(FakeStremio.KEY) })
        assertFalse(outcome.toString().contains(FakeStremio.KEY))
    }

    @Test
    fun `gives up when the phone doesn't approve in time, without reading the account`() = runBlocking {
        val host = FakeStremioHost().onJson("GET", "/api/v2/read", WAITING)
        val (store, _) = store()

        val outcome = importer(host, store, timeoutMs = 12_000).awaitAccountAddons(CODE)

        assertEquals(StremioLinkOutcome.Expired, outcome)
        assertEquals(4, host.seen.size)
        assertTrue(host.seen.none { it.method == "POST" })
    }

    @Test
    fun `Stremio refusing the code is final`() = runBlocking {
        val host = FakeStremioHost().onJson("GET", "/api/v2/read", """{"error":{"code":7,"message":"no"}}""")
        val (store, _) = store()
        assertEquals(StremioLinkOutcome.Failed(StremioError.Api(7)), importer(host, store).awaitAccountAddons(CODE))
        assertEquals(1, host.seen.size)
    }

    @Test
    fun `a failed account read is typed and free of the key`() = runBlocking {
        val host = FakeStremioHost()
            .onJson("GET", "/api/v2/read", LINKED)
            .onJson("POST", "/api/addonCollectionGet", """{"error":{"code":1,"message":"bad ${FakeStremio.KEY}"}}""")
        val (store, _) = store()
        val outcome = importer(host, store).awaitAccountAddons(CODE)
        assertEquals(StremioLinkOutcome.Failed(StremioError.Api(1)), outcome)
        assertFalse(outcome.toString().contains(FakeStremio.KEY))
    }

    @Test
    fun `nothing the import keeps or returns can hold the key`() {
        val classes = listOf(
            StremioImporter::class.java,
            StremioLinkOutcome.Ready::class.java,
            StremioLinkOutcome.Failed::class.java,
            ImportPreview::class.java,
            ImportCandidate::class.java,
            AddonImportReport::class.java,
        )
        classes.forEach { cls ->
            cls.declaredFields.forEach { field ->
                assertFalse("${cls.simpleName}.${field.name}", StremioAuthKey::class.java.isAssignableFrom(field.type))
                assertFalse("${cls.simpleName}.${field.name}", StremioLinkStatus::class.java.isAssignableFrom(field.type))
            }
        }
    }

    private companion object {
        const val KID = 7L
        const val OTHER_META_BASE = "https://meta.example/OTHER-CONFIG"
        val CODE = StremioLinkCode("AB12", "https://link.stremio.example/AB12")
        const val WAITING = """{"error":{"code":101,"message":"Invalid or expired token"}}"""
        val LINKED = """{"result":{"authKey":"${FakeStremio.KEY}"}}"""
        val ACCOUNT_LISTS = """{"result":{"addons":[{"transportUrl":"$LISTS_BASE/manifest.json","manifest":{"name":"Lists"}}]}}"""
    }
}
