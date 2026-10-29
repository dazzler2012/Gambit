package tv.own.owntv.gambit.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Placeholder addresses only — never a real configured addon (those carry the user's keys). */
class AddonUrlsTest {

    @Test fun stripsManifestAndSlash() {
        assertEquals("https://addon.example.com/cfg", AddonUrls.normalize("https://addon.example.com/cfg/manifest.json"))
        assertEquals("https://addon.example.com", AddonUrls.normalize("  https://addon.example.com/  "))
    }

    @Test fun stremioSchemeBecomesHttps() {
        assertEquals("https://addon.example.com/a", AddonUrls.normalize("stremio://addon.example.com/a/manifest.json"))
    }

    @Test fun keepsTheQuery() {
        val base = AddonUrls.normalize("https://addon.example.com/x/manifest.json?token=PLACEHOLDER")
        assertEquals("https://addon.example.com/x?token=PLACEHOLDER", base)
        assertEquals("https://addon.example.com/x/manifest.json?token=PLACEHOLDER", AddonUrls.manifestUrl(base!!))
    }

    @Test fun rejectsNonAddresses() {
        assertNull(AddonUrls.normalize(""))
        assertNull(AddonUrls.normalize("ftp://addon.example.com/manifest.json"))
        assertNull(AddonUrls.normalize("not a url"))
    }

    @Test fun hostIsAllThatIsShown() {
        val host = AddonUrls.hostOf("https://Addon.Example.com/secret-path-PLACEHOLDER?key=PLACEHOLDER")
        assertEquals("addon.example.com", host)
        assertFalse(host!!.contains("PLACEHOLDER"))
    }

    @Test fun parsesManifest() {
        val m = AddonManifest.parse(
            """{"id":"org.example.addon","name":"Example","version":"1.2.0","types":["movie","series"],
               "resources":["stream",{"name":"meta","types":["movie"]}],"catalogs":[{"type":"movie","id":"top"}]}""",
        )!!
        assertEquals("Example", m.name)
        assertEquals(listOf("movie", "series"), m.types)
        assertEquals(listOf("stream", "meta"), m.resources)
        assertEquals(1, m.catalogCount)
    }

    @Test fun rejectsNonManifest() {
        assertNull(AddonManifest.parse("<html></html>"))
        assertNull(AddonManifest.parse("""{"name":"no id"}"""))
    }
}
