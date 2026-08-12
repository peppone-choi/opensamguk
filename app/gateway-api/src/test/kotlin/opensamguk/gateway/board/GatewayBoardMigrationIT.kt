package opensamguk.gateway.board

import opensamguk.gateway.profile.ProfileIconSecureStorageTestConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(ProfileIconSecureStorageTestConfiguration::class)
class GatewayBoardMigrationIT {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `V40 creates a gateway-only board schema exactly once`() {
        // Given: a PostgreSQL database bootstrapped through all gateway Flyway migrations.
        val columns = jdbcTemplate.queryForList(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'gateway_board_post'
            """.trimIndent(),
            String::class.java,
        )
        val indexes = jdbcTemplate.queryForList(
            """
            SELECT indexname
            FROM pg_indexes
            WHERE schemaname = 'public' AND tablename = 'gateway_board_post'
            """.trimIndent(),
            String::class.java,
        )

        jdbcTemplate.execute(
            ClassPathResource("db/migration/V40__gateway_board.sql").inputStream.bufferedReader().use { it.readText() },
        )

        // Then: the board is account-scoped, not game-world scoped, and V40 is idempotent.
        assertTrue(columns.contains("author_account_id"))
        assertFalse(columns.contains("world_id"))
        assertTrue(indexes.contains("gateway_board_post_feed_idx"))
        assertTrue(indexes.contains("gateway_board_post_category_feed_idx"))
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '40' AND success",
                Int::class.java,
            ),
        )
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.postgresql.transactional-lock") { "false" }
        }
    }
}
