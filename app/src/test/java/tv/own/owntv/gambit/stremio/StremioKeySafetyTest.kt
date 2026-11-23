package tv.own.owntv.gambit.stremio

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Stremio package must have no way to log, save or sign out with the session key. This scans its
 * source, so a later change that adds any of these fails here instead of on the TV.
 */
class StremioKeySafetyTest {
    private val dir = File("src/main/java/tv/own/owntv/gambit/stremio")

    @Test
    fun `the stremio package never logs, stores or signs out`() {
        assertTrue("expected to run from the app module, cwd=${File(".").absolutePath}", dir.isDirectory)
        val sources = dir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue("no sources found", sources.isNotEmpty())
        val offences = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                FORBIDDEN.firstOrNull { it.containsMatchIn(line) }?.let { "${file.name}:${i + 1} matches ${it.pattern}" }
            }
        }
        assertTrue(offences.joinToString("\n"), offences.isEmpty())
    }

    private companion object {
        val FORBIDDEN = listOf(
            Regex("""\bLog\."""),
            Regex("""\bTimber\b"""),
            Regex("""\bprintln\("""),
            Regex("""printStackTrace"""),
            Regex("""SecretStore|AddonVault|SharedPreferences|DataStore"""),
            Regex("""androidx\.room|@Dao\b|@Entity\b"""),
            Regex("""(?i)"logout""""),
        )
    }
}
