package opensamguk.engine.boot

import opensamguk.infra.seed.ScenarioJson
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ScenarioSeedDisabledTest {

    @Test
    fun `disabled seed fence checks for an existing world but does not seed an empty database`() {
        val bootstrap = SeedBootstrap(scenarioCode = "scenario_1010", seedEnabled = false)
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        Mockito.`when`(jdbc.queryForObject("SELECT count(*) FROM world_state", Int::class.java)).thenReturn(0)

        assertFalse(bootstrap.ensureSeeded(jdbc))
        Mockito.verify(jdbc).queryForObject("SELECT count(*) FROM world_state", Int::class.java)
        Mockito.verifyNoMoreInteractions(jdbc)
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
