package opensamguk.infra.worldstate
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WorldStateRepositoryIT {
    @Autowired
    lateinit var repository: WorldStateRepository
    @Test
    fun `flyway baseline applied and world_state round-trips`() {
        val saved = repository.save(
            WorldStateEntity(
                scenarioCode = "scenario_2",
                currentYear = 190,
                currentMonth = 1,
                tickSeconds = 3600,
            )
        )
        assertNotNull(saved.id)
        val found = repository.findById(saved.id!!).orElseThrow()
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
        val saved = repository.save(
            WorldStateEntity(
                scenarioCode = "scenario_1010",
                currentYear = 181,
                currentMonth = 1,
                tickSeconds = 3600,
                startYear = 181,
                startTime = OffsetDateTime.parse("2026-05-30T01:00:00Z"),
                turnTerm = 60,
                isunited = 0,
                hiddenSeed = "8ebfeb6fa932a181ec9ef43b7473f4c9",
            )
        )
        val found = repository.findById(saved.id!!).orElseThrow()
        assertEquals(181, found.startYear)
        assertEquals(OffsetDateTime.parse("2026-05-30T01:00:00Z").toInstant(), found.startTime!!.toInstant())
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
