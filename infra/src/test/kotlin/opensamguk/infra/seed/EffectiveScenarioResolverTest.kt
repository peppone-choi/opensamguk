package opensamguk.infra.seed

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class EffectiveScenarioResolverTest {

    @Test
    fun `same-name external scenario overrides the bundled scenario`(@TempDir scenarioDir: Path) {
        Files.writeString(scenarioDir.resolve("scenario_1010.json"), scenarioJson("external"), StandardCharsets.UTF_8)

        val scenario = EffectiveScenarioResolver(scenarioDir.toString()).resolve("scenario_1010")

        assertEquals("external", scenario.title)
    }

    @Test
    fun `missing external scenario falls back to the bundled scenario`(@TempDir scenarioDir: Path) {
        val scenario = EffectiveScenarioResolver(scenarioDir.toString()).resolve("scenario_1010")

        assertTrue(scenario.generals.isNotEmpty())
    }

    @Test
    fun `malformed selected external scenario does not fall back to the bundled scenario`(@TempDir scenarioDir: Path) {
        Files.writeString(scenarioDir.resolve("scenario_1010.json"), "{ malformed", StandardCharsets.UTF_8)

        assertFails {
            EffectiveScenarioResolver(scenarioDir.toString()).resolve("scenario_1010")
        }
    }

    @Test
    fun `unreadable selected external scenario does not fall back to the bundled scenario`(@TempDir scenarioDir: Path) {
        Files.createDirectory(scenarioDir.resolve("scenario_1010.json"))

        assertFails {
            EffectiveScenarioResolver(scenarioDir.toString()).readScenarioJson("scenario_1010")
        }
    }

    private fun scenarioJson(title: String): String =
        """{"title":"$title","startYear":180,"map":{},"const":{},"nation":[],"general":[],"general_ex":[],"diplomacy":[]}"""
}
