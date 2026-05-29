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

@Testcontainers
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
