package tv.own.owntv.gambit.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gambit hides OwnTV's update-check and app-icon rows by key. If OwnTV renames a key, the filter
 * matches nothing and the row comes back with no error — this catches that. Source-scanned, like
 * SettingsSearchCoverageTest: the screen needs a whole Android graph to run.
 */
class GambitSettingsHiddenTest {

    private val source: String by lazy {
        val file = File("src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt")
        assertTrue("expected to run from the app module, cwd=${File(".").absolutePath}", file.isFile)
        file.readText()
    }

    @Test
    fun `the update-check and app-icon rows stay hidden`() {
        assertTrue(GambitHiddenSettingsKeys.containsAll(setOf("check_updates", "update_startup", "quick_check_update", "app_icon")))
    }

    @Test
    fun `every hidden key is still a row key in Settings`() {
        val rowKeys = Regex("""RootRow\(\s*"([a-z0-9_]+)"""").findAll(source).map { it.groupValues[1] }.toSet()
        assertTrue("could not read the RootRow keys", rowKeys.size > 10)
        assertEquals(
            "hidden keys that no longer name a Settings row — the row they were hiding may be back under a new key",
            emptyList<String>(),
            GambitHiddenSettingsKeys.filterNot { it in rowKeys }.sorted(),
        )
    }

    @Test
    fun `Settings still filters its rows and its search through the Gambit lists`() {
        assertTrue("root rows no longer filtered", "GambitHiddenSettingsKeys" in source)
        assertTrue("search results no longer filtered", "gambitHiddenSettingsTitles()" in source)
    }
}
