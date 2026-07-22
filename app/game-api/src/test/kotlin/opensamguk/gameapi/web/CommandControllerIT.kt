package opensamguk.gameapi.web

import opensamguk.common.world.WorldId

import opensamguk.common.wire.TurnDaemonStreamKeys
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals

/**
 * Task E3 — controller IT (MockMvc + Testcontainers postgres + redis). Boots the full game-api
 * context (JPA read repos + Flyway + Redis) and drives `POST /api/command/{code}`:
 *
 *  - AVAILABLE path (owned/supplied/funded city + `che_농지개간`) → `202` + a `requestId`;
 *  - forecast reservation path (non-owned city) → still `202`; the UI can mark it impossible from the
 *    catalog precheck, but the player may reserve future-turn/prediction commands and let the daemon
 *    re-evaluate at execution time.
 *
 * The fixture is the same baseline rows the E1/E2 tests use (nation 1 lvl7 / city 5 owned, agri
 * 4000<8000 / general 10 gold 4000; world_state year 200 startYear 190 → develCost 40 < 4000).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "opensamguk.profile=che:scenario_2",
        "opensamguk.world-id=1",
    ],
)
class CommandControllerIT {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var redis: StringRedisTemplate

    private lateinit var mockMvc: MockMvc
    private val commandStream = TurnDaemonStreamKeys.of("che:scenario_2", WorldId(1)).commandStream

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
        // clean slate per test (the context + containers are shared across the two tests).
        jdbc.update("DELETE FROM command_outbox")
        jdbc.update("DELETE FROM command_result")
        jdbc.update("DELETE FROM command_inbox")
        jdbc.update("DELETE FROM general_turn")
        jdbc.update("DELETE FROM general")
        jdbc.update("DELETE FROM city")
        jdbc.update("DELETE FROM nation")
        jdbc.update("DELETE FROM world_state")
        redis.delete(commandStream)
        seedBaseline(ownerNationId = 1)
    }

    @Test
    fun `AVAILABLE command returns 202 with a requestId`() {
        mockMvc.perform(
            post("/api/command/{code}", "che_농지개간")
                .param("generalId", "10")
                .contentType(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.requestId").isNotEmpty)

        // the daemon was poked: exactly one message on the command stream.
        assertEquals(1L, redis.opsForStream<Any, Any>().size(commandStream))
    }

    @Test
    fun `blocked forecast command is still reserved and pokes the daemon`() {
        // re-seed so the city belongs to a different nation -> OccupiedCity denies.
        jdbc.update("DELETE FROM city")
        seedCity(ownerNationId = 2)

        mockMvc.perform(
            post("/api/command/{code}", "che_농지개간")
                .param("generalId", "10")
                .contentType(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.requestId").isNotEmpty)

        assertEquals(1L, redis.opsForStream<Any, Any>().size(commandStream))
    }

    private fun seedBaseline(ownerNationId: Int) {
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, config, meta)
            VALUES (1, 'scenario_2', 200, 3, 3600, '{"startYear":190}'::jsonb, '{}'::jsonb)
            """.trimIndent()
        )
        jdbc.update(
            """
            INSERT INTO nation (world_id, id, name, color, capital_city_id, level, type_code)
            VALUES (1, 1, '위', '#0000ff', 5, 7, 'che_명사')
            """.trimIndent()
        )
        seedCity(ownerNationId)
        jdbc.update(
            """
            INSERT INTO general (
                world_id, id, name, nation_id, city_id, leadership, strength, intel, injury,
                experience, dedication, officer_level, gold, rice, turn_time, meta
            ) VALUES (
                1, 10, '순욱', 1, 5, 70, 30, 95, 0,
                1200, 900, 5, 4000, 3000, now(),
                '{"explevel":4,"intel_exp":12,"max_domestic_critical":3.5}'::jsonb
            )
            """.trimIndent()
        )
    }

    private fun seedCity(ownerNationId: Int) {
        jdbc.update(
            """
            INSERT INTO city (
                world_id, id, name, level, nation_id, supply_state, front_state,
                pop, pop_max, agri, agri_max, comm, comm_max, secu, secu_max,
                trust, trade, def, def_max, wall, wall_max, region, meta
            ) VALUES (
                1, 5, '허창', 5, ?, 1, 0,
                50000, 100000, 4000, 8000, 3000, 8000, 1000, 2000,
                82, 100, 500, 1000, 800, 1500, 3, '{}'::jsonb
            )
            """.trimIndent(),
            ownerNationId,
        )
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
    }
}
