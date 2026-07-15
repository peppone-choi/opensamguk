package opensamguk.engine

import opensamguk.engine.run.TurnDaemonRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration," +
            "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
    ],
)
class EmptyWorldBootIT {
    @LocalServerPort
    var port: Int = 0
    @Autowired
    lateinit var rest: TestRestTemplate
    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var daemonRunner: TurnDaemonRunner

    @AfterEach
    fun stopDaemonBeforeContainerShutdown() {
        daemonRunner.stop()
        assertFalse(daemonRunner.isRunning, "daemon thread must join before Testcontainers stops PostgreSQL")
    }

    @Test
    fun `daemon starts and idles when admin has not created a world yet`() {
        val worldCount = jdbc.queryForObject("SELECT count(*) FROM world_state", Int::class.java) ?: -1
        assertEquals(0, worldCount, "test starts with the intentional empty-server invariant")
        val body = rest.getForObject(
            "http://localhost:$port/admin/turn-daemon/status", String::class.java,
        )
        assertTrue(body!!.contains("\"loopAlive\":true"), "status body: $body")
        assertTrue(body.contains("\"state\":\"running\""), "status body: $body")
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
            registry.add("management.health.redis.enabled") { "false" }
            registry.add("SCENARIO_SEED_ENABLED") { "false" }
            registry.add("opensamguk.daemon.enabled") { "true" }
            registry.add("opensamguk.daemon.idle-poll-ms") { "25" }
        }
    }
}
