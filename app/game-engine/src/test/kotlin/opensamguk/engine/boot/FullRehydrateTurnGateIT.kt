package opensamguk.engine.boot

import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.world.WorldId
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralAccessLog
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.Troop
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.mock
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullRehydrateTurnGateIT {

    private val continuousWorldId = WorldId(501)
    private val restartWorldId = WorldId(502)
    private val poisonWorldId = WorldId(503)
    private val generalId = 1501
    private val cityId = 2501
    private val nationId = 3501
    private val secondNationId = 3502
    private val fixtureHiddenSeed = "00000000000000000000000000000000"
    private val fixtureStartYear = 200
    private val start = Instant.parse("0200-01-01T00:00:00Z")
    private val firstTick = start.plusSeconds(3600)
    private val secondTick = start.plusSeconds(7200)

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var namedJdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        assumeTrue(
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
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()
        jdbc = JdbcTemplate(dataSource)
        namedJdbc = NamedParameterJdbcTemplate(dataSource)
        executor = JdbcFlushExecutor(namedJdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)))
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `restart between identical reserved turns matches the uninterrupted world`() {
        seedWorld(continuousWorldId, "기준세계", "기준장수")
        seedWorld(restartWorldId, "기준세계", "기준장수")
        seedWorld(poisonWorldId, "독성세계", "독성장수")
        val poisonBefore = poisonPersistentSignature()
        reserveTwoTurns(continuousWorldId, "continuous")
        reserveTwoTurns(restartWorldId, "restart")

        val continuous = fixture(continuousWorldId)

        val continuousFirst = continuous.service.runTick(firstTick)
        assertResolved(continuousFirst, "che_기술연구")
        assertTechnologyFlush(continuousFirst)
        fixture(restartWorldId).let { preRestart ->
            val restartFirst = preRestart.service.runTick(firstTick)
            assertResolved(restartFirst, "che_기술연구")
            assertTechnologyFlush(restartFirst)
        }

        assertEquals(firstTick, continuous.world.getState().lastTurnTime)
        assertEquals(firstTick.toString(), persistedClock(continuousWorldId))
        assertEquals(firstTick.toString(), persistedClock(restartWorldId))
        assertEquals(
            reservedTurnSignature(continuousWorldId),
            reservedTurnSignature(restartWorldId),
            "the general_turn queue must be durable before the restart boundary",
        )
        assertEquals("continuous-2", queuedRequestId(continuousWorldId))
        assertEquals("restart-2", queuedRequestId(restartWorldId))
        assertEquals(
            commandResultSignature(continuousWorldId),
            commandResultSignature(restartWorldId),
            "the terminal-result channel must be durable before the restart boundary",
        )

        val continuousSecond = continuous.service.runTick(secondTick)
        assertResolved(continuousSecond, "che_농지개간")
        assertFarmFlush(continuousSecond)
        val restarted = fixture(restartWorldId)
        val restartSecond = restarted.service.runTick(secondTick)
        assertResolved(restartSecond, "che_농지개간")
        assertFarmFlush(restartSecond)

        assertEquals(secondTick, continuous.world.getState().lastTurnTime)
        assertEquals(secondTick, restarted.world.getState().lastTurnTime)
        assertEquals(
            residentHotSignature(continuous.world),
            residentHotSignature(restarted.world),
            "N + 1 must see the same hot world whether N stayed resident or was rehydrated from PostgreSQL",
        )
        val continuousReloaded = fixture(continuousWorldId).world
        val restartReloaded = fixture(restartWorldId).world
        assertEquals(
            residentHotSignature(continuous.world),
            residentHotSignature(continuousReloaded),
            "the uninterrupted N + 1 hot state must survive a second discard/reload",
        )
        assertEquals(
            residentHotSignature(restarted.world),
            residentHotSignature(restartReloaded),
            "the restarted N + 1 hot state must survive a second discard/reload",
        )
        assertEquals(
            residentHotSignature(continuousReloaded),
            residentHotSignature(restartReloaded),
            "the two durable N + 1 snapshots must be equivalent after both worlds reload",
        )
        assertEquals(persistedStateSignature(continuousWorldId), persistedStateSignature(restartWorldId))
        assertEquals(rankSignature(continuousWorldId), rankSignature(restartWorldId))
        assertEquals(reservedTurnSignature(continuousWorldId), reservedTurnSignature(restartWorldId))
        assertEquals(commandResultSignature(continuousWorldId), commandResultSignature(restartWorldId))
        assertEquals(commandOutboxSignature(continuousWorldId), commandOutboxSignature(restartWorldId))
        assertEquals(listOf("continuous-1", "continuous-2"), commandResultRequestIds(continuousWorldId))
        assertEquals(listOf("restart-1", "restart-2"), commandResultRequestIds(restartWorldId))
        assertEquals(listOf("continuous-1", "continuous-2"), commandOutboxRequestIds(continuousWorldId))
        assertEquals(listOf("restart-1", "restart-2"), commandOutboxRequestIds(restartWorldId))
        val continuousLogs = koreanLogHex(continuousWorldId)
        assertTrue(continuousLogs.isNotEmpty(), "the turn must persist non-empty Korean action logs")
        assertEquals(continuousLogs, koreanLogHex(restartWorldId))
        assertFalse(
            restarted.world.listGenerals().any { it.name == "독성장수" },
            "the restarted world must never load the same local id from the poison world",
        )
        assertFalse(restarted.world.listCities().any { it.name == "독성세계 도시" })
        assertFalse(restarted.world.listNations().any { it.name.startsWith("독성세계") })
        assertFalse(restarted.world.listTroops().any { it.name == "독성세계 부대" })
        assertEquals(poisonBefore, poisonPersistentSignature(), "neither world may flush same-local-id poison rows")
    }

    private fun fixture(worldId: WorldId): Fixture {
        val world = InMemoryTurnWorld(loader(worldId).buildSnapshot())
        val loadedState = world.getState()
        val hiddenSeed = loadedState.meta["hiddenSeed"] as? String
            ?: error("world ${worldId.value} must load hiddenSeed from world_state.meta")
        val startYear = (loadedState.meta["startYear"] as? Number)?.toInt()
            ?: error("world ${worldId.value} must load startYear from world_state.meta")
        assertEquals(fixtureHiddenSeed, hiddenSeed, "fixture precondition: loader-provided hidden seed")
        assertEquals(fixtureStartYear, startYear, "fixture precondition: loader-provided start year")
        assertEquals(start.toString(), loadedState.meta["startTime"], "fixture precondition: loader-provided start time")
        assertEquals(loadedState.currentYear, (loadedState.meta["currentYear"] as? Number)?.toInt())
        assertEquals(loadedState.currentMonth, (loadedState.meta["currentMonth"] as? Number)?.toInt())
        assertEquals(loadedState.currentPhase, (loadedState.meta["currentPhase"] as? Number)?.toInt())
        assertEquals(loadedState.lastTurnTime.toString(), loadedState.meta["lastTurnTime"])
        val handler = ReservedTurnHandler(
            world = world,
            registry = CommandRegistry(GeneralActionPipeline()),
            hiddenSeed = hiddenSeed,
            startYear = startYear,
        )
        val reservedTurns = ReservedTurnRepository(namedJdbc)
        val redis = mock(StringRedisTemplate::class.java)
        val lifecycle = TurnDaemonLifecycle(
            world = world,
            handler = handler,
            pullGeneralTurnOf = { id -> handler.recorder.recordGeneralTurnPull(id) },
            reservedActionOf = { id -> reservedTurns.readReserved(worldId, id, 0) },
        )
        val commandStream = object : RedisCommandStream(redis, "rehydrate-${worldId.value}", worldId) {
            override fun readEnvelopes(blockMs: Long): List<TurnDaemonCommandEnvelope> = emptyList()
        }
        return Fixture(
            world = world,
            service = TurnRunService(
                world = world,
                commandStream = commandStream,
                lifecycle = lifecycle,
                handler = handler,
                flushExecutor = executor,
                realtimePublisher = mock(RealtimePublisher::class.java),
                commandBlockMs = 1,
            ),
        )
    }

    private fun loader(worldId: WorldId): WorldSnapshotLoader = WorldSnapshotLoader(
        jdbc = jdbc,
        seedBootstrap = SeedBootstrap(
            scenarioCode = "scenario_0",
            seedEnabled = false,
            worldId = worldId,
        ),
        worldId = worldId,
    )

    private fun seedWorld(worldId: WorldId, worldName: String, generalName: String) {
        val worldMeta = """{"startYear":$fixtureStartYear,"startTime":"$start","hiddenSeed":"$fixtureHiddenSeed"}"""
        jdbc.update(
            """
            INSERT INTO world_state
                (id, scenario_code, current_year, current_month, current_phase, tick_seconds, start_time, meta)
            VALUES (?, 'scenario_0', ?, 1, 1, 3600, CAST(? AS timestamptz), CAST(? AS jsonb))
            """.trimIndent(),
            worldId.value,
            fixtureStartYear,
            start.toString(),
            worldMeta,
        )
        jdbc.update(
            """
            INSERT INTO game_kv (world_id, "table", namespace, key, value)
            VALUES (?, 'game_env', '', 'currentYear', '999'::jsonb),
                   (?, 'game_env', '', 'currentMonth', '99'::jsonb),
                   (?, 'game_env', '', 'currentPhase', '3'::jsonb)
            """.trimIndent(),
            worldId.value,
            worldId.value,
            worldId.value,
        )
        jdbc.update(
            """
            INSERT INTO nation (world_id, id, name, color, capital_city_id, gold, rice, tech, power, level, type_code)
            VALUES (?, ?, ?, '#123456', ?, 1000, 1000, 0, 0, 2, 'normal'),
                   (?, ?, ?, '#654321', 0, 0, 0, 0, 0, 1, 'normal')
            """.trimIndent(),
            worldId.value,
            nationId,
            "$worldName 제일국",
            cityId,
            worldId.value,
            secondNationId,
            "$worldName 제이국",
        )
        jdbc.update(
            """
            INSERT INTO city
                (world_id, id, name, level, nation_id, supply_state, front_state, pop, pop_max,
                 agri, agri_max, comm, comm_max, secu, secu_max, trust, trade, def, def_max,
                 wall, wall_max, region, meta)
            VALUES
                (?, ?, ?, 5, ?, 1, 0, 50000, 100000,
                 1000, 20000, 1000, 20000, 500, 1000, 50, 100, 1000, 2000,
                 1000, 2000, 1, CAST('{"trust":50}' AS jsonb))
            """.trimIndent(),
            worldId.value,
            cityId,
            "$worldName 도시",
            nationId,
        )
        jdbc.update(
            """
            INSERT INTO general
                (world_id, id, name, nation_id, city_id, leadership, strength, intel, injury,
                 experience, dedication, officer_level, gold, rice, turn_time, meta)
            VALUES
                (?, ?, ?, ?, ?, 70, 70, 80, 0, 1000, 1000, 0, 100000, 1000, CAST(? AS timestamptz),
                 CAST('{"explevel":10,"dedlevel":4,"intel_exp":3,"max_domestic_critical":0,"killturn":80}' AS jsonb))
            """.trimIndent(),
            worldId.value,
            generalId,
            generalName,
            nationId,
            cityId,
            start.toString(),
        )
        jdbc.update(
            """
            INSERT INTO rank_data (world_id, nation_id, general_id, type, value)
            VALUES (?, ?, ?, 'kill', 7)
            """.trimIndent(),
            worldId.value,
            nationId,
            generalId,
        )
        jdbc.update(
            """
            INSERT INTO troop (world_id, troop_leader, nation, name)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            worldId.value,
            generalId,
            nationId,
            "$worldName 부대",
        )
        jdbc.update(
            """
            INSERT INTO diplomacy (world_id, src_nation_id, dest_nation_id, state_code, term, meta)
            VALUES (?, ?, ?, 1, 3, '{}'::jsonb)
            """.trimIndent(),
            worldId.value,
            nationId,
            secondNationId,
        )
        jdbc.update(
            """
            INSERT INTO general_access_log
                (world_id, general_id, user_id, last_refresh, refresh, refresh_total, refresh_score, refresh_score_total)
            VALUES (?, ?, 77, CAST(? AS timestamptz), 1, 2, 3, 4)
            """.trimIndent(),
            worldId.value,
            generalId,
            start.toString(),
        )
    }

    private fun reserveTwoTurns(worldId: WorldId, requestPrefix: String) {
        val turns = ReservedTurnRepository(namedJdbc)
        turns.reserve(worldId, generalId, 0, "che_기술연구", requestId = "$requestPrefix-1")
        turns.reserve(worldId, generalId, 1, "che_농지개간", requestId = "$requestPrefix-2")
    }

    private fun assertResolved(result: TurnRunService.TickResult, actionCode: String) {
        assertEquals(1, result.handled.size, "one due general must run at this deterministic tick")
        assertEquals(actionCode, result.handled.single().definition.key)
        assertFalse(result.handled.single().fellBack, "$actionCode must resolve rather than fall back")
    }

    private fun assertOneWorldStateFlush() {
        assertEquals(
            1,
            executor.lastOps().count { it.table == "world_state" },
            "each TurnRunService.runTick must commit exactly one world_state operation",
        )
    }

    private fun assertTechnologyFlush(result: TurnRunService.TickResult) {
        assertTrue(result.flushedGenerals > 0, "technology research must flush its general mutation")
        assertTrue(result.flushedLogs > 0, "technology research must flush its Korean action log")
        assertOneWorldStateFlush()
        assertFlushedTables(
            "world_state",
            "general",
            "nation",
            "log_entry",
            "general_turn_pull",
            "command_result",
            "command_outbox",
        )
    }

    private fun assertFarmFlush(result: TurnRunService.TickResult) {
        assertTrue(result.flushedGenerals > 0, "farm development must flush its general mutation")
        assertTrue(result.flushedCities > 0, "farm development must flush its city mutation")
        assertTrue(result.flushedLogs > 0, "farm development must flush its Korean action log")
        assertOneWorldStateFlush()
        assertFlushedTables(
            "world_state",
            "general",
            "city",
            "log_entry",
            "general_turn_pull",
            "command_result",
            "command_outbox",
        )
    }

    private fun assertFlushedTables(vararg expected: String) {
        val actual = executor.lastOps().filter { it.count > 0 }.map { it.table }.toSet()
        for (table in expected) {
            assertTrue(table in actual, "expected $table in the real JDBC flush, got $actual")
        }
    }

    private fun persistedClock(worldId: WorldId): String = jdbc.queryForObject(
        "SELECT meta ->> 'lastTurnTime' FROM world_state WHERE id = ?",
        String::class.java,
        worldId.value,
    )!!

    private fun persistedStateSignature(worldId: WorldId): List<String> = jdbc.query(
        """
        SELECT current_year, current_month, current_phase, world_version, writer_epoch,
               meta ->> 'lastTurnTime' AS last_turn_time
          FROM world_state
         WHERE id = ?
        """.trimIndent(),
        { rs, _ ->
            listOf(
                rs.getInt("current_year").toString(),
                rs.getInt("current_month").toString(),
                rs.getInt("current_phase").toString(),
                rs.getLong("world_version").toString(),
                rs.getLong("writer_epoch").toString(),
                rs.getString("last_turn_time"),
            )
        },
        worldId.value,
    ).single()

    private fun rankSignature(worldId: WorldId): List<String> = jdbc.query(
        """
        SELECT general_id, nation_id, type, value
          FROM rank_data
         WHERE world_id = ?
         ORDER BY general_id, type, id
        """.trimIndent(),
        { rs, _ -> "${rs.getInt("general_id")}:${rs.getInt("nation_id")}:${rs.getString("type")}:${rs.getInt("value")}" },
        worldId.value,
    )

    private fun reservedTurnSignature(worldId: WorldId): List<String> {
        val turns = ReservedTurnRepository(namedJdbc)
        return (0..2).map { index ->
            turns.readReserved(worldId, generalId, index).let { turn ->
                "$index:${turn.actionCode}:${turn.argJson}:${turn.brief}"
            }
        }
    }

    private fun queuedRequestId(worldId: WorldId): String? =
        ReservedTurnRepository(namedJdbc).readReserved(worldId, generalId, 0).requestId

    private fun commandResultSignature(worldId: WorldId): List<String> = jdbc.query(
        """
        SELECT result_seq, terminal_status, result_type, ok, committed_world_version,
               (result_payload - 'requestId' - 'sentAt')::text AS normalized_payload
          FROM command_result
         WHERE world_id = ?
         ORDER BY request_id, result_seq
        """.trimIndent(),
        { rs, _ ->
            "${rs.getInt("result_seq")}:${rs.getString("terminal_status")}:${rs.getString("result_type")}:" +
                "${rs.getBoolean("ok")}:${rs.getLong("committed_world_version")}:${rs.getString("normalized_payload")}"
        },
        worldId.value,
    )

    private fun commandOutboxSignature(worldId: WorldId): List<String> = jdbc.query(
        """
        SELECT event_type, payload_schema_version, (payload - 'requestId' - 'sentAt')::text AS normalized_payload
          FROM command_outbox
         WHERE world_id = ?
         ORDER BY request_id, event_id
        """.trimIndent(),
        { rs, _ -> "${rs.getString("event_type")}:${rs.getInt("payload_schema_version")}:${rs.getString("normalized_payload")}" },
        worldId.value,
    )

    private fun commandResultRequestIds(worldId: WorldId): List<String> = jdbc.queryForList(
        "SELECT request_id FROM command_result WHERE world_id = ? ORDER BY request_id",
        String::class.java,
        worldId.value,
    )

    private fun commandOutboxRequestIds(worldId: WorldId): List<String> = jdbc.queryForList(
        "SELECT request_id FROM command_outbox WHERE world_id = ? ORDER BY request_id",
        String::class.java,
        worldId.value,
    )

    private fun koreanLogHex(worldId: WorldId): List<String> = jdbc.query(
        """
        SELECT encode(convert_to(text, 'UTF8'), 'hex')
          FROM log_entry
         WHERE world_id = ?
         ORDER BY id
        """.trimIndent(),
        { rs, _ -> rs.getString(1) },
        worldId.value,
    )

    private fun poisonPersistentSignature(): List<String> = listOf(
        jdbc.queryForObject(
            "SELECT meta ->> 'lastTurnTime' FROM world_state WHERE id = ?",
            String::class.java,
            poisonWorldId.value,
        ) ?: "<no-clock>",
        jdbc.queryForObject(
            """
            SELECT concat_ws('|', name, gold::text, rice::text, experience::text, dedication::text,
                             turn_time::text, meta::text)
              FROM general
             WHERE world_id = ? AND id = ?
            """.trimIndent(),
            String::class.java,
            poisonWorldId.value,
            generalId,
        )!!,
        jdbc.queryForObject(
            """
            SELECT concat_ws('|', name, agri::text, comm::text, trust::text, meta::text)
              FROM city
             WHERE world_id = ? AND id = ?
            """.trimIndent(),
            String::class.java,
            poisonWorldId.value,
            cityId,
        )!!,
        jdbc.queryForObject(
            """
            SELECT concat_ws('|', name, gold::text, rice::text, tech::text, power::text, meta::text)
              FROM nation
             WHERE world_id = ? AND id = ?
            """.trimIndent(),
            String::class.java,
            poisonWorldId.value,
            nationId,
        )!!,
        jdbc.queryForObject(
            "SELECT name FROM troop WHERE world_id = ? AND troop_leader = ?",
            String::class.java,
            poisonWorldId.value,
            generalId,
        )!!,
        jdbc.queryForObject(
            "SELECT value FROM rank_data WHERE world_id = ? AND general_id = ? AND type = 'kill'",
            Int::class.java,
            poisonWorldId.value,
            generalId,
        )!!.toString(),
        jdbc.queryForObject(
            "SELECT state_code::text || ':' || term::text FROM diplomacy WHERE world_id = ? AND src_nation_id = ? AND dest_nation_id = ?",
            String::class.java,
            poisonWorldId.value,
            nationId,
            secondNationId,
        )!!,
        jdbc.queryForObject(
            "SELECT refresh_score_total FROM general_access_log WHERE world_id = ? AND general_id = ?",
            Int::class.java,
            poisonWorldId.value,
            generalId,
        )!!.toString(),
        jdbc.queryForObject(
            "SELECT count(*) FROM general_turn WHERE world_id = ?",
            Int::class.java,
            poisonWorldId.value,
        )!!.toString(),
        jdbc.queryForObject(
            "SELECT count(*) FROM command_result WHERE world_id = ?",
            Int::class.java,
            poisonWorldId.value,
        )!!.toString(),
        jdbc.queryForObject(
            "SELECT count(*) FROM command_outbox WHERE world_id = ?",
            Int::class.java,
            poisonWorldId.value,
        )!!.toString(),
        jdbc.queryForObject(
            "SELECT count(*) FROM log_entry WHERE world_id = ?",
            Int::class.java,
            poisonWorldId.value,
        )!!.toString(),
    )

    private fun residentHotSignature(world: InMemoryTurnWorld): HotSignature = HotSignature(
        state = world.getState().copy(id = 0),
        generals = world.listGenerals().sortedBy { it.id }.map { it.copy(initialTurns = emptyList()) },
        cities = world.listCities().sortedBy { it.id },
        nations = world.listNations().sortedBy { it.id },
        troops = world.listTroops().sortedBy { it.id },
        diplomacy = world.listDiplomacy().sortedWith(compareBy({ it.fromNationId }, { it.toNationId })),
        accessLogs = world.listAccessLogs().sortedBy { it.generalId },
    )

    private data class Fixture(
        val world: InMemoryTurnWorld,
        val service: TurnRunService,
    )

    private data class HotSignature(
        val state: TurnWorldState,
        val generals: List<TurnGeneral>,
        val cities: List<City>,
        val nations: List<Nation>,
        val troops: List<Troop>,
        val diplomacy: List<TurnDiplomacy>,
        val accessLogs: List<GeneralAccessLog>,
    )
}
