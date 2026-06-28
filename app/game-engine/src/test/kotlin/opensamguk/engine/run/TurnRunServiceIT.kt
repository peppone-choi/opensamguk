package opensamguk.engine.run

import opensamguk.common.wire.RealtimeEvent
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.TurnDaemonStreamKeys
import opensamguk.common.wire.WIRE_PAYLOAD_FIELD
import opensamguk.common.wire.WireJson
import opensamguk.common.wire.gameEventChannel
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.connection.stream.ObjectRecord
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.test.Test as KTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1 Task F5 — the daemon round-trip IT (Testcontainers redis + postgres).
 *
 * Seeds the world (in-memory source of truth + the matching DB pre-state rows) and a reserved
 * `che_농지개간` for one AVAILABLE general, then drives [TurnRunService.runTick] ONCE and asserts:
 *  (a) the general + city post-state rows were flushed to Postgres (gold paid, agriculture raised,
 *      `meta.intel_exp` bumped) — proving the recorder→FlushPayload→JdbcFlushExecutor write path,
 *  (b) a `turnCompleted` [RealtimeEvent] was published on the per-profile realtime channel,
 *  (c) exactly one `log_entry` ACTION row was written.
 *
 * Processed-count gated: the lifecycle drains the (single) due general in one pass and the flush
 * fires exactly once at the clean turn boundary — never mid-pass.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TurnRunServiceIT {

    private val profile = "che:scenario_2"
    private val streamKeys = TurnDaemonStreamKeys.of(profile)
    private val channel = gameEventChannel(profile)

    // Fixture mirrors ReservedTurnHandlerTest: AVAILABLE general (owned, supplied, non-front city,
    // gold >> cost), che_농지개간 raises agriculture and pays the develop cost.
    private val hiddenSeed = "00000000000000000000000000000000"
    private val year = 200
    private val month = 1
    private val startYear = 184
    private val generalId = 42
    private val cityId = 7
    private val nationId = 1
    private val t0: Instant = Instant.parse("0200-01-01T12:34:00Z")
    private val calendarStartTime: Instant = Instant.parse("0200-01-01T12:00:00Z")
    private val tickSeconds = 3600

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var flushExecutor: JdbcFlushExecutor

    private lateinit var redis: GenericContainer<*>
    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate

    @BeforeAll
    fun setUpClass() {
        // --- postgres + Flyway baseline -----------------------------------------------------------
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()
        val dataSource: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        flushExecutor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)))

        // --- redis (command stream + realtime pub/sub) --------------------------------------------
        redis = GenericContainer("redis:7-alpine").withExposedPorts(6379)
        redis.start()
        connectionFactory = LettuceConnectionFactory(
            RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379)),
        )
        connectionFactory.afterPropertiesSet()
        template = StringRedisTemplate(connectionFactory)
        template.afterPropertiesSet()
    }

    @AfterAll
    fun tearDownClass() {
        if (this::connectionFactory.isInitialized) connectionFactory.destroy()
        if (this::redis.isInitialized) redis.stop()
        if (this::postgres.isInitialized) postgres.stop()
    }

    @KTest
    fun `one tick resolves the reserved che_농지개간 flushes the post-state and publishes turnCompleted`() {
        assertTrue(redis.isRunning, "redis:7-alpine container must be running")

        // --- DB pre-state (the rows the flush UPDATEs) -------------------------------------------
        seedDbPreState()

        // --- the reserved general turn lives in the general_turn ring, NOT on the command stream --
        val reservedRepo = ReservedTurnRepository(jdbc)
        reservedRepo.reserve(generalId = generalId, turnIdx = 0, actionCode = "che_농지개간")

        // --- the in-memory world (the daemon source of truth, matching the DB pre-state) ---------
        val world = InMemoryTurnWorld(worldSnapshot())
        val registry = CommandRegistry(GeneralActionPipeline())
        val handler = ReservedTurnHandler(world, registry, hiddenSeed, startYear)
        val lifecycle = TurnDaemonLifecycle(world, handler) { gid ->
            reservedRepo.readReserved(gid, 0)
        }
        val commandStream = RedisCommandStream(template, profile)
        val realtimePublisher = RealtimePublisher(template, profile)

        // --- a control command is enqueued by game-api on the command stream (P1 drains it) ------
        enqueueControlCommand()

        // --- subscribe to the realtime channel BEFORE the tick (catch the turnCompleted) ---------
        val received = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        // NB: explicit receiver — inside an `apply{}` block `connectionFactory` would shadow to the
        // container's own (null) getConnectionFactory(), not this IT's field.
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)
        container.addMessageListener(
            MessageListener { msg, _ -> received.set(String(msg.body)); latch.countDown() },
            ChannelTopic(channel),
        )
        container.afterPropertiesSet()
        container.start()
        try {
            val subscribeDeadline = System.currentTimeMillis() + 3000
            while (!container.isRunning && System.currentTimeMillis() < subscribeDeadline) Thread.sleep(20)

            // --- RUN ONE TICK -------------------------------------------------------------------
            // PHP 선택 게이트(TurnExecutionHelper.php:237)는 `turntime < %s`(STRICT <)다. production은
            // runTick을 nextRunTime()=lastTurnTime+tick(=turnTime보다 STRICT 미래)로 호출한다 — turnTime과
            // 정확히 같은 시각은 due가 아니다. 과거 fixture는 runTime=t0(==turnTime)로 inclusive `<=` 버그에
            // 의존했다. strict-< 교정 후엔 turnTime보다 미래 시각을 넘겨야 그 장수가 due가 된다.
            val runTime = t0.plusSeconds(1)
            val result = service(world, commandStream, lifecycle, handler, realtimePublisher).runTick(runTime)

            // the reserved action resolved (not the rest fallback) and exactly one general processed
            assertEquals(1, result.handled.size, "exactly one due general drained in one pass")
            val handled = result.handled.single()
            assertFalse(handled.fellBack, "AVAILABLE general resolves che_농지개간, not the rest fallback")
            assertEquals("che_농지개간", handled.definition.key)
            assertEquals(1, result.flushedGenerals)
            assertEquals(1, result.flushedCities)
            assertEquals(1, result.flushedLogs)

            // --- (a) general + city post-state flushed to Postgres ------------------------------
            val gRow = jdbc.queryForMap(
                "SELECT gold, meta::text AS meta FROM general WHERE id = :id",
                MapSqlParameterSource("id", generalId),
            )
            assertTrue(intOf(gRow["gold"]) < 100_000, "gold decreased by reqGold: ${gRow["gold"]}")
            assertTrue(
                stringOf(gRow["meta"]).contains("\"intel_exp\""),
                "general.meta carries intel_exp progress: ${gRow["meta"]}",
            )

            val cRow = jdbc.queryForMap(
                "SELECT agri, agri_max FROM city WHERE id = :id",
                MapSqlParameterSource("id", cityId),
            )
            assertTrue(intOf(cRow["agri"]) > 1000, "agriculture increased: ${cRow["agri"]}")
            assertEquals(20000, intOf(cRow["agri_max"]), "agri_max unchanged")

            // the flushed DB row equals the world post-state (one source of truth)
            assertEquals(world.getGeneralById(generalId)!!.gold, intOf(gRow["gold"]))
            assertEquals(world.getCityById(cityId)!!.agriculture, intOf(cRow["agri"]))

            // --- (c) exactly one log_entry ACTION row written -----------------------------------
            val logCount = jdbc.queryForObject(
                "SELECT count(*) FROM log_entry", MapSqlParameterSource(), Int::class.java,
            )
            assertEquals(1, logCount, "exactly one action log row written")
            val logRow = jdbc.queryForMap(
                "SELECT scope::text AS scope, category::text AS category, general_id, year, month, phase FROM log_entry",
                MapSqlParameterSource(),
            )
            assertEquals("GENERAL", logRow["scope"])
            assertEquals("ACTION", logRow["category"])
            assertEquals(generalId, intOf(logRow["general_id"]))
            assertEquals(year, intOf(logRow["year"]))
            assertEquals(month, intOf(logRow["month"]))
            assertEquals(1, intOf(logRow["phase"]))

            val wRow = jdbc.queryForMap(
                "SELECT current_year, current_month, current_phase FROM world_state WHERE id = 1",
                MapSqlParameterSource(),
            )
            assertEquals(year, intOf(wRow["current_year"]))
            assertEquals(month, intOf(wRow["current_month"]))
            assertEquals(1, intOf(wRow["current_phase"]))

            // --- (b) a turnCompleted realtime event was published -------------------------------
            assertTrue(latch.await(5, TimeUnit.SECONDS), "turnCompleted delivered on the realtime channel")
            val decoded = WireJson.decodeFromString(RealtimeEvent.serializer(), received.get()!!)
            assertTrue(decoded is RealtimeEvent.TurnCompleted, "the published event is turnCompleted")
            assertEquals(runTime.toString(), (decoded as RealtimeEvent.TurnCompleted).at)
            assertEquals(t0.toString(), decoded.lastTurnTime, "lastTurnTime = the pre-tick world clock")
            assertEquals(1, decoded.turnPhase)
            assertEquals("상순", decoded.turnPhaseText)
        } finally {
            container.stop()
            template.delete(streamKeys.commandStream)
        }
    }

    private fun service(
        world: InMemoryTurnWorld,
        commandStream: RedisCommandStream,
        lifecycle: TurnDaemonLifecycle,
        handler: ReservedTurnHandler,
        realtimePublisher: RealtimePublisher,
    ) = TurnRunService(world, commandStream, lifecycle, handler, flushExecutor, realtimePublisher)

    // --- seeds --------------------------------------------------------------------------------------

    private fun seedDbPreState() {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) " +
                "VALUES (1, 'scenario_2', :y, :m, :t)",
            MapSqlParameterSource().addValue("y", year).addValue("m", month).addValue("t", tickSeconds),
        )
        jdbc.update(
            """
            INSERT INTO general
                (id, name, nation_id, city_id, leadership, strength, intel, injury,
                 experience, dedication, officer_level, gold, rice, turn_time, meta)
            VALUES
                (:id, 'g42', :nation, :city, 70, 70, 80, 0, 0, 0, 0, 100000, 1000, now(),
                 CAST('{"explevel":10,"intel_exp":3,"max_domestic_critical":0}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", generalId).addValue("nation", nationId).addValue("city", cityId),
        )
        jdbc.update(
            """
            INSERT INTO city
                (id, name, level, nation_id, supply_state, front_state, pop, pop_max,
                 agri, agri_max, comm, comm_max, secu, secu_max, trust, trade, def, def_max,
                 wall, wall_max, region, meta)
            VALUES
                (:id, 'c7', 5, :nation, 1, 0, 50000, 100000,
                 1000, 20000, 1000, 20000, 500, 1000, 50, 100, 1000, 2000,
                 1000, 2000, 1, CAST('{"trust":50}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", cityId).addValue("nation", nationId),
        )
    }

    private fun worldSnapshot() = WorldSnapshot(
        state = TurnWorldState(
            id = 1,
            currentYear = year,
            currentMonth = month,
            tickSeconds = tickSeconds,
            lastTurnTime = t0,
            meta = linkedMapOf("startYear" to year, "startTime" to calendarStartTime.toString()),
        ),
        generals = listOf(
            TurnGeneral(
                id = generalId, name = "g42", nationId = nationId, cityId = cityId, troopId = 0,
                stats = GeneralStats(leadership = 70, strength = 70, intelligence = 80),
                experience = 0, dedication = 0, officerLevel = 0, gold = 100_000, rice = 1000, injury = 0,
                // killturn>0: 살아있는 장수는 양수 killturn을 가진다(PHP는 $gameStor->killturn에서 시드). strict-<
                // 교정 후 drain 꼬리(updateTurnTime, TurnExecutionHelper.php:185)의 killturn<=0 kill 게이트가
                // 동작하므로, killturn 미설정(0) 장수는 tail에서 kill되어 flush 대상에서 사라진다 → 양수로 생존.
                turnTime = t0, meta = linkedMapOf("explevel" to 10, "intel_exp" to 3, "max_domestic_critical" to 0.0, "killturn" to 80),
            ),
        ),
        cities = listOf(
            City(
                id = cityId, name = "c7", nationId = nationId, level = 5,
                agriculture = 1000, agricultureMax = 20000, commerce = 1000, commerceMax = 20000,
                supplyState = 1, frontState = 0,
                // carry the seeded develop/defense columns so the widened step-7 city UPDATE (FF2)
                // round-trips them faithfully instead of zeroing them.
                population = 50000, populationMax = 100000,
                security = 500, securityMax = 1000,
                defence = 1000, defenceMax = 2000,
                wall = 1000, wallMax = 2000,
                trade = 100, region = 1,
                meta = linkedMapOf("trust" to 50),
            ),
        ),
        nations = listOf(Nation(id = nationId, name = "n1", color = "#000", level = 2, capitalCityId = 99)),
    )

    private fun enqueueControlCommand() {
        val envelope = TurnDaemonCommandEnvelope(
            requestId = "tick-1",
            sentAt = "0200-01-01T00:00:00.000Z",
            command = TurnDaemonCommand.GetStatus(requestId = "tick-1"),
        )
        val payload = WireJson.encodeToString(TurnDaemonCommandEnvelope.serializer(), envelope)
        val record: ObjectRecord<String, Map<String, String>> = StreamRecords
            .newRecord()
            .ofObject(mapOf(WIRE_PAYLOAD_FIELD to payload))
            .withStreamKey(streamKeys.commandStream)
        @Suppress("UNCHECKED_CAST")
        template.opsForStream<Any, Any>().add(record as ObjectRecord<String, Any>)
    }

    private fun intOf(v: Any?): Int = (v as Number).toInt()
    private fun stringOf(v: Any?): String = v as String
}
