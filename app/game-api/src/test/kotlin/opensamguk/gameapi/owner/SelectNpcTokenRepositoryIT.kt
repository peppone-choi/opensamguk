package opensamguk.gameapi.owner

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.assertEquals

@Testcontainers
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SelectNpcTokenRepositoryIT {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tokens: SelectNpcTokenRepository

    @Test
    fun `pick result writes to the jsonb token column`() {
        val saved = tokens.save(
            SelectNpcTokenEntity(
                ownerId = 7L,
                validUntil = Instant.parse("2026-06-02T00:01:00Z"),
                pickMoreFrom = Instant.parse("2000-01-01T01:00:00Z"),
                pickResult = linkedMapOf(
                    "10" to linkedMapOf("generalId" to 10, "name" to "여포"),
                    "__pickMoreSeconds" to 10,
                ),
                nonce = 1,
                createdAt = Instant.parse("2026-06-02T00:00:00Z"),
                updatedAt = Instant.parse("2026-06-02T00:00:00Z"),
            ),
        )
        tokens.flush()

        val storedType = jdbc.queryForObject(
            "select pg_typeof(pick_result)::text from select_npc_token where id = ?",
            String::class.java,
            saved.id,
        )
        val storedName = jdbc.queryForObject(
            "select pick_result -> '10' ->> 'name' from select_npc_token where id = ?",
            String::class.java,
            saved.id,
        )

        assertEquals("jsonb", storedType)
        assertEquals("여포", storedName)
        assertEquals("여포", (tokens.findById(saved.id!!).orElseThrow().pickResult["10"] as Map<*, *>)["name"])
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
