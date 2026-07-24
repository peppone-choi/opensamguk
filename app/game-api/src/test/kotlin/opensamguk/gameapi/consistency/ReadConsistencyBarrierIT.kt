package opensamguk.gameapi.consistency

import opensamguk.gameapi.config.ReadBarrierJdbcTemplate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "opensamguk.profile=che:scenario_2",
        "opensamguk.world-id=1",
        "opensamguk.read-barrier.max-wait-ms=400",
        "opensamguk.read-barrier.poll-interval-ms=10",
        "opensamguk.read-barrier.retry-after-ms=75",
        "opensamguk.read-barrier.pool.max-size=1",
        "opensamguk.read-barrier.pool.connection-timeout-ms=250",
        "management.health.redis.enabled=false",
    ],
)
class ReadConsistencyBarrierIT {
    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var barrierJdbc: ReadBarrierJdbcTemplate

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
        jdbc.update("DELETE FROM world_state")
        jdbc.update(
            """
            INSERT INTO world_state (
                id, scenario_code, current_year, current_month, tick_seconds, world_version, writer_epoch, config, meta
            ) VALUES (
                1, 'scenario_2', 200, 3, 3600, 7, 10, '{"startYear":190}'::jsonb, '{}'::jsonb
            )
            """.trimIndent(),
        )
    }

    @Test
    fun `primary minVersion timeout returns current and required versions`() {
        assertEquals(postgres.jdbcUrl, barrierJdbc.jdbcUrl)
        assertEquals(1, barrierJdbc.maximumPoolSize)
        assertEquals(250L, barrierJdbc.connectionTimeoutMs)

        mockMvc.perform(get("/api/barrier-probe").param("minVersion", "8"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value("VERSION_NOT_VISIBLE"))
            .andExpect(jsonPath("$.currentVersion").value(7))
            .andExpect(jsonPath("$.requiredVersion").value(8))
            .andExpect(jsonPath("$.retryAfterMs").value(75))
    }

    @Test
    fun `primary minVersion waits until concurrent version commit becomes visible`() {
        val startUpdate = CountDownLatch(1)
        val updater = Thread {
            assertTrue(startUpdate.await(1, TimeUnit.SECONDS))
            jdbc.update("UPDATE world_state SET world_version = 8 WHERE id = 1")
        }
        updater.start()
        try {
            startUpdate.countDown()
            mockMvc.perform(get("/api/barrier-probe").param("minVersion", "8"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.result").value(true))
        } finally {
            updater.join()
        }
    }

    @Test
    fun `saturated barrier pool returns VERSION_NOT_VISIBLE within bounded acquisition timeout`() {
        val heldConnection = requireNotNull(barrierJdbc.jdbc.jdbcTemplate.dataSource).connection
        try {
            val elapsedMs = measureTimeMillis {
                mockMvc.perform(get("/api/barrier-probe").param("minVersion", "8"))
                    .andExpect(status().isConflict)
                    .andExpect(jsonPath("$.status").value("VERSION_NOT_VISIBLE"))
                    .andExpect(jsonPath("$.requiredVersion").value(8))
                    .andExpect(jsonPath("$.retryAfterMs").value(75))
            }
            assertTrue(elapsedMs < 1_500, "elapsedMs=$elapsedMs")
        } finally {
            heldConnection.close()
        }
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

@RestController
class ReadConsistencyBarrierProbeController {
    @GetMapping("/api/barrier-probe")
    fun read(): Map<String, Boolean> = mapOf("result" to true)
}
