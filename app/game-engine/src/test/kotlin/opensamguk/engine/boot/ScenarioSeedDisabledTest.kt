package opensamguk.engine.boot

import opensamguk.infra.seed.ScenarioJson
import org.springframework.jdbc.core.JdbcTemplate
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ScenarioSeedDisabledTest {

    @Test
    fun `disabled seed fence prevents defensive bootstrap from touching the database`() {
        val bootstrap = SeedBootstrap(scenarioCode = "scenario_1010", seedEnabled = false)

        assertFalse(bootstrap.ensureSeeded(JdbcTemplate()))
    }

    @Test
    fun `scenario directory overrides classpath scenario json`() {
        val dir = Files.createTempDirectory("scenario-dir").toFile()
        try {
            val scenario = File(dir, "scenario_1010.json")
            scenario.writeText("""{"title":"external","startYear":180,"map":{},"const":{},"nation":[],"general":[],"general_ex":[],"diplomacy":[]}""")
            val bootstrap = SeedBootstrap(
                scenarioCode = "scenario_1010",
                seedEnabled = true,
                scenarioDir = scenario.parentFile.absolutePath,
            )

            assertEquals("external", ScenarioJson.loadScenario(bootstrap.readScenarioJson()).title)
        } finally {
            dir.deleteRecursively()
        }
    }
}
