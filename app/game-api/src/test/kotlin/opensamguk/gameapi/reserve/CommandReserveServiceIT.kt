package opensamguk.gameapi.reserve

import opensamguk.common.wire.RunReason
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonStreamKeys
import opensamguk.common.wire.WIRE_PAYLOAD_FIELD
import opensamguk.common.wire.decodeCommandEnvelope
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task E3 — reserve IT (Testcontainers postgres + redis). Reserves `che_농지개간` through
 * [CommandReserveService] and proves BOTH effects of step 2:
 *
 *  - the durable `general_turn` row exists with the reserved action-code + arg (DB = source of truth);
 *  - the MUTATION (command) stream has EXACTLY ONE message whose decoded [TurnDaemonCommandEnvelope]
 *    is the EXISTING P0-B control signal `Run(reason=POKE)` carrying the returned `requestId`.
 *
 * The stream is read raw (not via the engine consumer — game-api does not depend on `:app:game-engine`),
 * asserting the wire bytes the daemon's `RedisCommandStream` would consume. Plain JDBC write path.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandReserveServiceIT {

    private val profile = "che:scenario_2"
    private val commandStream = TurnDaemonStreamKeys.of(profile).commandStream

    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var service: CommandReserveService

    @Test
    fun `reserve writes general_turn AND pokes the daemon on the command stream`() {
        assertTrue(postgres.isRunning, "postgres:16-alpine container must be running")
        assertTrue(redis.isRunning, "redis:7-alpine container must be running")

        val result = service.reserve(
            generalId = 10,
            actionCode = "che_농지개간",
            turnIdx = 0,
            argJson = """{"amount":100}""",
        )

        // --- DB: the durable reservation exists with the reserved action + arg ---
        val reserved = ReservedTurnRepository(jdbc).readReserved(worldId = WorldId(1), generalId = 10, turnIdx = 0)
        assertEquals("che_농지개간", reserved.actionCode)
        assertEquals("""{"amount": 100}""", reserved.argJson)

        // --- Redis: exactly one message, decoding to Run(POKE) with the returned requestId ---
        val records = redisTemplate.opsForStream<Any, Any>()
            .read(StreamOffset.create(commandStream, ReadOffset.from("0")))
            .orEmpty()
        assertEquals(1, records.size, "exactly one poke published")
        val payload = records.single().value[WIRE_PAYLOAD_FIELD].toString()
        val envelope = decodeCommandEnvelope(payload)
        assertEquals(result.requestId, envelope.requestId)
        val command = assertIs<TurnDaemonCommand.Run>(envelope.command)
        assertEquals(RunReason.POKE, command.reason)
    }

    @BeforeAll
    fun setUp() {
        val dockerAvailable = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        assumeTrue(dockerAvailable, "Docker unavailable — command reserve IT skipped (not failed)")
        postgres.start()
        redis.start()

        val dataSource: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        val jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.execute(
            """
            CREATE TABLE world_state (
                id integer PRIMARY KEY
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE general_turn (
                id serial PRIMARY KEY,
                world_id integer NOT NULL REFERENCES world_state(id),
                general_id integer NOT NULL,
                turn_idx integer NOT NULL,
                action_code text NOT NULL,
                arg jsonb NOT NULL DEFAULT '{}'::jsonb,
                brief text NOT NULL DEFAULT '휴식',
                created_at timestamptz NOT NULL DEFAULT now(),
                UNIQUE (world_id, general_id, turn_idx)
            )
            """.trimIndent(),
        )
        jdbcTemplate.update(
            "INSERT INTO world_state (id) VALUES (1)",
        )
        jdbc = NamedParameterJdbcTemplate(dataSource)

        val config = RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379))
        connectionFactory = LettuceConnectionFactory(config)
        connectionFactory.afterPropertiesSet()
        redisTemplate = StringRedisTemplate(connectionFactory)
        redisTemplate.afterPropertiesSet()

        // deterministic clock + requestId so the assertions are byte-stable.
        service = CommandReserveService(
            reservedTurns = ReservedTurnRepository(jdbc),
            redis = redisTemplate,
            registry = CommandRegistry(GeneralActionPipeline()),
            processWorld = GameApiProcessWorld(1),
            profile = profile,
            clock = Clock.fixed(Instant.parse("0200-01-01T00:00:00.000Z"), ZoneOffset.UTC),
            requestIds = { "req-e3-fixed" },
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::connectionFactory.isInitialized) connectionFactory.destroy()
        if (postgres.isRunning) postgres.stop()
        if (redis.isRunning) redis.stop()
    }

    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)
    }
}
