package opensamguk.engine.boot

import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class ScenarioSeedDisabledTest {

    @Test
    fun `disabled seed fence returns without touching the database`() {
        val bootstrap = SeedBootstrap(
            scenarioCode = "scenario_1010",
            seedEnabled = false,
            worldId = opensamguk.common.world.WorldId(1),
        )
        val jdbc = Mockito.mock(JdbcTemplate::class.java)

        assertFalse(bootstrap.ensureSeeded(jdbc))
        Mockito.verifyNoInteractions(jdbc)
    }

    @Test
    fun `seed bootstrap resolves a same-name external scenario before the bundled scenario`() {
        val dir = Files.createTempDirectory("scenario-dir").toFile()
        try {
            val scenario = File(dir, "scenario_1010.json")
            scenario.writeText("""{"title":"external","startYear":180,"map":{},"const":{},"nation":[],"general":[],"general_ex":[],"diplomacy":[]}""")
            val bootstrap = SeedBootstrap(
                scenarioCode = "scenario_1010",
                seedEnabled = true,
                scenarioDir = scenario.parentFile.absolutePath,
                worldId = opensamguk.common.world.WorldId(1),
            )

            assertEquals("external", bootstrap.loadScenario().title)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `seed bootstrap does not fall back after a selected external scenario fails to parse`() {
        val dir = Files.createTempDirectory("scenario-dir").toFile()
        try {
            File(dir, "scenario_1010.json").writeText("{ malformed")
            val bootstrap = SeedBootstrap(
                scenarioCode = "scenario_1010",
                scenarioDir = dir.absolutePath,
                worldId = opensamguk.common.world.WorldId(1),
            )

            assertFails { bootstrap.loadScenario() }
        } finally {
            dir.deleteRecursively()
        }
    }
}
