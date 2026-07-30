package opensamguk.infra.worldstate
import opensamguk.infra.read.SideReadRepositoryConfiguration
import opensamguk.infra.read.WorldOneScopeConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SideReadRepositoryConfiguration::class, WorldOneScopeConfiguration::class)
class WorldStateRepositoryIT {
    @Autowired
    lateinit var repository: WorldStateRepository
    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `flyway baseline applied and world_state round-trips`() {
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (1, 'scenario_2', 190, 1, 3600)
            """.trimIndent(),
        )
        val found = repository.findProcessWorld()!!
        assertEquals("scenario_2", found.scenarioCode)
        assertEquals(190, found.currentYear)
        assertEquals(3600, found.tickSeconds)
        // FC1: a baseline-shape entity (calendar columns omitted) reads back isunited=0, the rest null.
        assertEquals(0, found.isunited)
        assertEquals(null, found.startYear)
        assertEquals(null, found.hiddenSeed)
    }
    @Test
    fun `FC1 -- world-state read exposes the calendar columns + hidden_seed for ServerClock`() {
        jdbc.update(
            """
            INSERT INTO world_state (
                id, scenario_code, current_year, current_month, tick_seconds,
                start_year, start_time, turn_term, isunited, hidden_seed
            ) VALUES (
                1, 'scenario_1010', 181, 1, 3600,
                181, TIMESTAMPTZ '2026-05-30 01:00:00+00', 60, 0,
                '8ebfeb6fa932a181ec9ef43b7473f4c9'
            )
            """.trimIndent(),
        )
        val found = repository.findById(1).orElseThrow()
        assertEquals(181, found.startYear)
        assertEquals("2026-05-30T01:00Z", found.startTime.toString())
        assertEquals(60, found.turnTerm)
        assertEquals(0, found.isunited)
        // The config-sourced live hidden_seed is a 32-char lowercase hex (bin2hex(random_bytes(16))).
        assertEquals("8ebfeb6fa932a181ec9ef43b7473f4c9", found.hiddenSeed)
        assertEquals(32, found.hiddenSeed!!.length)
    }
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
