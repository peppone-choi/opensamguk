package opensamguk.infra.persistence

import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
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

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V30ProfileIconMigrationIT {
    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `V30 maps profile icon change instant to PostgreSQL timestamptz`() {
        val changedAt = Instant.parse("2026-07-17T01:02:03.123456Z")
        val saved = userRepository.saveAndFlush(
            UserEntity(
                username = "profile-icon-migration",
                password = "encoded",
                profileIconChangedAt = changedAt,
            ),
        )

        val reloaded = userRepository.findById(saved.id).orElseThrow()
        assertEquals(changedAt, reloaded.profileIconChangedAt)
        assertEquals(false, reloaded.profileIconManaged)
        assertEquals(
            "timestamp with time zone",
            jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'profile_icon_changed_at'",
                String::class.java,
            ),
        )
        assertEquals(
            "boolean",
            jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'profile_icon_managed'",
                String::class.java,
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
        }
    }
}
