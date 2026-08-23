package opensamguk.gateway.config

import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AdminSeederPostgresIT {
    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val passwordEncoder = BCryptPasswordEncoder()

    @BeforeEach
    fun resetUsers() {
        userRepository.deleteAll()
    }

    @Test
    fun `admin boot seed preserves an occupied username nickname and creates a valid unique fallback`() {
        userRepository.saveAndFlush(
            UserEntity(username = "regular-user", password = "encoded", nickname = "PePpOnE"),
        )
        userRepository.saveAndFlush(
            UserEntity(username = "fallback-owner", password = "encoded", nickname = "관리자"),
        )

        AdminSeeder(userRepository, jdbcTemplate, passwordEncoder, "peppone", "admin-password")
            .run(org.springframework.boot.DefaultApplicationArguments())

        val regular = userRepository.findByUsername("regular-user").orElseThrow()
        val admin = userRepository.findByUsername("peppone").orElseThrow()
        assertEquals("PePpOnE", regular.nickname)
        assertEquals("관리자-d7724391", admin.nickname)
        assertEquals("ADMIN", admin.role)
        assertEquals(6, admin.grade)
        assertTrue(requireNotNull(admin.nickname).length in 2..20)
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
