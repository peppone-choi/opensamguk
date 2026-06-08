package opensamguk.engine.boot

import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.Test
import kotlin.test.assertFalse

class ScenarioSeedDisabledTest {

    @Test
    fun `disabled seed fence prevents defensive bootstrap from touching the database`() {
        val bootstrap = SeedBootstrap(scenarioCode = "scenario_1010", seedEnabled = false)

        assertFalse(bootstrap.ensureSeeded(JdbcTemplate()))
    }
}
