package opensamguk.engine.boot

import opensamguk.common.constants.ScenarioLifecycleMeta
import opensamguk.engine.config.EngineEventConfig
import opensamguk.engine.world.WorldEventContextFactory
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.logic.event.EventCondition
import opensamguk.logic.event.EventTarget
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.temporal.ChronoUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1b boot/tick gate — proves the FULL fresh-DB → playable-world path end-to-end:
 *  1. fresh Postgres + Flyway baseline,
 *  2. [SeedBootstrap.ensureSeeded] seeds `scenario_1010` (229 active generals / 24 cities / 2 nations),
 *  3. [WorldSnapshotLoader.buildSnapshot] materializes the [opensamguk.engine.turn.WorldSnapshot],
 *  4. an [InMemoryTurnWorld] is constructed from it and a [TurnDaemonLifecycle] tick ADVANCES the turn
 *     loop (the seeded ring is all 휴식 → each due general resolves the rest no-op) GREEN, no exception,
 *  5. a SECOND seed is a no-op.
 *
 * Uses the pure in-memory [TurnDaemonLifecycle.runTick] (NO redis) — the lightest faithful path that
 * exercises snapshot-load → due-general selection → per-general turn handling → world-clock advance.
 * Skipped (assumeTrue), not failed, when Docker is unavailable. Mirrors the TurnRunServiceIT setup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// 순차 end-to-end 게이트: 원본 테스트가 "첫 ensureSeeded가 fresh world를 시드한다"를 단언하므로
// W0-8 rehydrate 테스트(역시 시드 필요)는 반드시 그 뒤에 와야 한다 — JUnit 기본 메서드 순서는 비보장.
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ScenarioBootIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var named: NamedParameterJdbcTemplate
    private lateinit var bootstrap: SeedBootstrap
    private lateinit var loader: WorldSnapshotLoader
    private var dockerAvailable = false

    @BeforeAll
    fun setUpClass() {
        dockerAvailable = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        if (!dockerAvailable) return

        postgres = PostgreSQLContainer("postgres:16-alpine")
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
        jdbc = JdbcTemplate(dataSource)
        named = NamedParameterJdbcTemplate(dataSource)
        bootstrap = SeedBootstrap("scenario_1010")
        loader = WorldSnapshotLoader(jdbc, bootstrap)
    }

    @AfterAll
    fun tearDownClass() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    @Order(1)
    fun `seed then load then advance one turn green`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario boot IT skipped (not failed)")

        // 2. seed
        assertTrue(bootstrap.ensureSeeded(jdbc), "first ensureSeeded seeds the fresh world")
        assertEquals(229, count("general"))
        assertEquals(94, count("city")) // che 풀맵: 점유 24 + 공백지 70 = 94 (cities_1010.json)
        assertEquals(2, count("nation"))

        // 3. load snapshot → 4. build the in-memory world
        val snapshot = loader.buildSnapshot()
        assertEquals(229, snapshot.generals.size)
        assertEquals(94, snapshot.cities.size) // che 풀맵: 점유 24 + 공백지 70 = 94
        assertEquals(2, snapshot.nations.size)
        assertEquals(0, snapshot.troops.size, "no troops at scenario start")
        assertEquals(2, snapshot.diplomacy.size)
        assertEquals(0, (snapshot.state.meta["isunited"] as? Number)?.toInt() ?: -1, "isunited loaded from world_state column")
        val firstCity = snapshot.cities.first()
        val storedCity = jdbc.queryForMap("SELECT trust, dead FROM city WHERE id = ?", firstCity.id)
        assertEquals((storedCity["trust"] as Number).toDouble(), (firstCity.meta["trust"] as Number).toDouble())
        assertEquals((storedCity["dead"] as Number).toInt(), firstCity.dead)
        val seedStartYear = (snapshot.state.meta["startYear"] as Number).toInt()
        val seedStartMonth = 1
        val killturns = snapshot.generals.map { (it.meta["killturn"] as Number).toInt() }
        assertTrue(
            killturns.toSet().size > 1,
            "seeded generals load with per-general killturns, not one global collapse",
        )
        assertTrue(
            snapshot.generals.all {
                val deadYear = (it.meta["deadyear"] as Number).toInt()
                val killturn = (it.meta["killturn"] as Number).toInt()
                val min = ScenarioLifecycleMeta.killturnFor(deadYear, seedStartYear, seedStartMonth, 0)
                val max = ScenarioLifecycleMeta.killturnFor(deadYear, seedStartYear, seedStartMonth, 11)
                killturn in min..max && killturn % 3 == 0
            },
            "seeded generals load with PHP deadyear+jitter lifecycle meta",
        )
        assertTrue(
            snapshot.generals.any {
                val deadYear = (it.meta["deadyear"] as Number).toInt()
                (it.meta["killturn"] as Number).toInt() !=
                    ScenarioLifecycleMeta.killturnFor(deadYear, seedStartYear, seedStartMonth, 0)
            },
            "at least one seeded general carries the PHP killturn jitter draw",
        )
        assertTrue(
            snapshot.generals.all { (it.meta["deadyear"] as? Number)?.toInt() ?: 0 > snapshot.state.currentYear },
            "seeded generals load with PHP deadyear lifecycle meta",
        )
        val firstGeneral = snapshot.generals.first()
        val storedGeneral = jdbc.queryForMap("SELECT picture, image_server FROM general WHERE id = ?", firstGeneral.id)
        assertEquals(storedGeneral["picture"], firstGeneral.meta["picture"])
        assertEquals((storedGeneral["image_server"] as Number).toInt(), firstGeneral.meta["image_server"])

        val world = InMemoryTurnWorld(snapshot)
        val registry = CommandRegistry(GeneralActionPipeline())
        val hiddenSeed = world.getState().meta["hiddenSeed"] as String
        val startYear = (world.getState().meta["startYear"] as Number).toInt()
        val handler = ReservedTurnHandler(world, registry, hiddenSeed, startYear)
        val reservedRepo = ReservedTurnRepository(named)
        val lifecycle = TurnDaemonLifecycle(world, handler) { gid -> reservedRepo.readReserved(gid, 0) }

        // 4. ADVANCE: drive one tick strictly after every seeded general's turn_time so they are all due
        // (the seeded ring is all 휴식 → each resolves the rest no-op). Must not throw.
        val runTime = world.listGenerals().maxOf { it.turnTime }.plus(1, ChronoUnit.SECONDS)
        val dueBefore = lifecycle.dueGenerals(runTime).size
        assertTrue(dueBefore > 0, "all seeded generals are due after their turn_time")

        val handled = lifecycle.runTick(runTime)
        assertTrue(handled.isNotEmpty(), "the turn loop advanced at least one due general")
        // The seeded ring is all 휴식 → every advanced general resolves the rest action (no exception).
        assertTrue(handled.all { it.definition.key == "휴식" }, "every advanced turn resolved 휴식")
        assertEquals(2, world.listNations().size, "seeded nations survive the first due-turn tick")

        // 5. second seed is a no-op (emptiness gate).
        assertTrue(!bootstrap.ensureSeeded(jdbc), "second ensureSeeded is a no-op")
        assertEquals(229, count("general"), "no duplicate generals after second seed")
    }

    @Test
    @Order(2)
    fun `W0-8 -- city state가 재기동 rehydrate를 살아남는다`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario boot IT skipped (not failed)")

        // @Order(2): 원본 테스트의 "첫 ensureSeeded" 단언 뒤에 실행 — 여기서는 시드 보장만(멱등 no-op).
        bootstrap.ensureSeeded(jdbc)
        val cityId = jdbc.queryForObject("SELECT id FROM city ORDER BY id ASC LIMIT 1", Int::class.java)!!

        // 직전 달 RaiseDisaster가 남긴 재해 코드(예: 4)를 흉내 — DB에 영속된 상태로 가정.
        jdbc.update("UPDATE city SET state = 4 WHERE id = $cityId")

        // 재기동 경로: WorldSnapshotLoader가 DB → in-memory City로 state를 실어와야 한다.
        // (V14 이전에는 메모리 전용이라 0으로 떨어졌다 — P0-36 재기동 유실.)
        val snapshot = loader.buildSnapshot()
        val rehydrated = snapshot.cities.first { it.id == cityId }
        assertEquals(4, rehydrated.state, "city.state는 restart-rehydrate를 살아남아야 한다")

        // 다른 테스트와의 간섭 방지 — 원복.
        jdbc.update("UPDATE city SET state = 0 WHERE id = $cityId")
    }

    @Test
    @Order(3)
    fun `politics and charm survive restart rehydrate`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario boot IT skipped (not failed)")

        bootstrap.ensureSeeded(jdbc)
        val generalId = jdbc.queryForObject("SELECT id FROM general ORDER BY id ASC LIMIT 1", Int::class.java)!!
        jdbc.update("UPDATE general SET politics = 73, charm = 84 WHERE id = $generalId")

        val rehydrated = loader.buildSnapshot().generals.first { it.id == generalId }

        assertEquals(73, rehydrated.stats.politics)
        assertEquals(84, rehydrated.stats.charm)
    }

    @Test
    @Order(4)
    fun `game and nation kv survive restart rehydrate`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario boot IT skipped (not failed)")

        bootstrap.ensureSeeded(jdbc)
        val nationId = jdbc.queryForObject("SELECT id FROM nation ORDER BY id ASC LIMIT 1", Int::class.java)!!
        jdbc.update(
            """INSERT INTO game_kv ("table", namespace, key, value) VALUES ('game_env', 'game_env', 'tnmt_pattern', '[0,1,2]'::jsonb) ON CONFLICT ("table", namespace, key) DO UPDATE SET value = EXCLUDED.value""",
        )
        jdbc.update(
            """INSERT INTO game_kv ("table", namespace, key, value) VALUES ('game_env', 'game_env', 'isunited', '99'::jsonb) ON CONFLICT ("table", namespace, key) DO UPDATE SET value = EXCLUDED.value""",
        )
        jdbc.update(
            """INSERT INTO nation_env (namespace, key, value) VALUES ($nationId, 'available_war_setting_cnt', '3'::jsonb) ON CONFLICT (namespace, key) DO UPDATE SET value = EXCLUDED.value""",
        )

        val snapshot = loader.buildSnapshot()

        assertEquals(listOf(0, 1, 2), snapshot.state.meta["tnmt_pattern"])
        assertEquals(0, snapshot.state.meta["isunited"])
        assertEquals(
            3,
            (snapshot.nations.first { it.id == nationId }.meta["nation_env"] as Map<*, *>)["available_war_setting_cnt"],
        )
    }

    @Test
    @Order(5)
    fun `event store boot rows and mutation flush survive restart`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario boot IT skipped (not failed)")

        bootstrap.ensureSeeded(jdbc)
        val configuredStore = EngineEventConfig().eventStore(jdbc, bootstrap)
        val deletedId = jdbc.queryForObject(
            "SELECT id FROM event WHERE target_code = 'destroy_nation' AND priority = 1000 ORDER BY id ASC LIMIT 1",
            Int::class.java,
        )!!
        assertEquals(count("event"), configuredStore.allRows().size)

        val recorder = ChangeRecorder()
        configuredStore.bindMutationSink(recorder::recordEventMutation)
        configuredStore.delete(deletedId)
        val insertedId = configuredStore.insert(
            targetCode = "month",
            priority = 1234,
            condition = EventCondition.ConstBool(true),
            actions = emptyList(),
        )
        val world = InMemoryTurnWorld(loader.buildSnapshot())
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        val dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        JdbcFlushExecutor(
            NamedParameterJdbcTemplate(dataSource),
            TransactionTemplate(DataSourceTransactionManager(dataSource)),
        ).flush(payload)

        assertEquals(0, countWhere("event", "id = $deletedId"))
        assertEquals(1, countWhere("event", "id = $insertedId"))
        val restarted = EngineEventConfig().eventStore(jdbc, bootstrap)
        assertTrue(restarted.allRows().none { it.id == deletedId })
        assertTrue(restarted.allRows().any { it.id == insertedId && it.priority == 1234 })
    }

    @Test
    @Order(6)
    fun `deferred scenario generals appear at adult year and survive restart`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario boot IT skipped (not failed)")

        bootstrap.ensureSeeded(jdbc)
        val eventConfig = EngineEventConfig()
        val eventStore = eventConfig.eventStore(jdbc, bootstrap)
        val world = InMemoryTurnWorld(loader.buildSnapshot())
        val recorder = ChangeRecorder()
        eventStore.bindMutationSink(recorder::recordEventMutation)
        val pipeline = GeneralActionPipeline()
        val dispatcher = eventConfig.eventDispatcher(eventStore, eventConfig.eventActionFactory())
        val contextFactory = WorldEventContextFactory.create(
            world = world,
            recorder = recorder,
            pipeline = pipeline,
            hiddenSeed = world.getState().meta["hiddenSeed"] as String,
            startYear = world.getState().meta["startYear"] as Int,
            mapName = world.getState().meta["map"] as String,
            eventStore = eventStore,
        )

        assertEquals(0, countWhere("general", "name = 'ⓝ소제1'"))
        dispatcher.run(
            EventTarget.MONTH,
            contextFactory = contextFactory,
            envSupplier = { linkedMapOf("year" to 182, "month" to 1, "phase" to 1) },
        )

        assertEquals(20, world.listGenerals().count { it.name.startsWith("ⓝ") && it.age == 14 })
        assertFalse(eventStore.allRows().any { row -> row.actions.any { it.args.any { arg -> arg.toString().contains("소제1") } } })

        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        val dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        JdbcFlushExecutor(
            NamedParameterJdbcTemplate(dataSource),
            TransactionTemplate(DataSourceTransactionManager(dataSource)),
        ).flush(payload)

        val appearedId = jdbc.queryForObject("SELECT id FROM general WHERE name = 'ⓝ소제1'", Int::class.java)!!
        assertEquals(30, countWhere("general_turn", "general_id = $appearedId"))
        assertEquals(37, countWhere("rank_data", "general_id = $appearedId"))
        assertEquals(0, countWhere("event", "action::text LIKE '%소제1%'"))
        assertTrue(loader.buildSnapshot().generals.any { it.id == appearedId && it.name == "ⓝ소제1" })
    }

    @Test
    @Order(7)
    fun `existing world does not resurrect a removed scenario NPC`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario boot IT skipped (not failed)")
        val missing = jdbc.queryForMap(
            """
            SELECT id, name
              FROM general
             WHERE npc_state >= 2 AND officer_level <> 12 AND troop_id = 0
             ORDER BY id
             LIMIT 1
            """.trimIndent(),
        )
        val generalId = (missing["id"] as Number).toInt()
        val generalName = missing["name"] as String
        jdbc.update("DELETE FROM general_turn WHERE general_id = ?", generalId)
        jdbc.update("DELETE FROM rank_data WHERE general_id = ?", generalId)
        jdbc.update("DELETE FROM general WHERE id = ?", generalId)

        assertFalse(bootstrap.ensureSeeded(jdbc))

        val pendingBefore = jdbc.queryForObject(
            """
            SELECT count(*)
              FROM event
              CROSS JOIN LATERAL jsonb_array_elements(action) action_row
             WHERE action_row ->> 0 = 'RegNPC'
               AND action_row ->> 2 = ?
            """.trimIndent(),
            Int::class.java,
            generalName,
        )
        assertEquals(0, pendingBefore)
        assertFalse(bootstrap.ensureSeeded(jdbc))
        val pendingAfter = jdbc.queryForObject(
            """
            SELECT count(*)
              FROM event
              CROSS JOIN LATERAL jsonb_array_elements(action) action_row
             WHERE action_row ->> 0 = 'RegNPC'
               AND action_row ->> 2 = ?
            """.trimIndent(),
            Int::class.java,
            generalName,
        )
        assertEquals(0, pendingAfter)
    }

    @Test
    @Order(8)
    fun `archived nation ids seed allocator high-water on restart`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario boot IT skipped (not failed)")
        bootstrap.ensureSeeded(jdbc)
        val archivedId = (
            jdbc.queryForObject(
                "SELECT greatest(coalesce((SELECT max(id) FROM nation), 0), coalesce((SELECT max(nation) FROM ng_old_nations), 0))",
                Int::class.java,
            ) ?: 0
            ) + 50
        val currentServerId = loader.buildSnapshot().serverId!!
        jdbc.update(
            """
            INSERT INTO ng_games (server_id, date, season, scenario, scenario_name, env)
            VALUES ('newest-wrong-server', now() + interval '1 day', 1, 0, 'wrong', '{}'::jsonb)
            ON CONFLICT (server_id) DO UPDATE SET date = EXCLUDED.date
            """.trimIndent(),
        )

        try {
            jdbc.update(
                """
                INSERT INTO ng_old_nations (server_id, nation, data)
                VALUES (?, ?, '{}'::jsonb)
                ON CONFLICT (server_id, nation) DO UPDATE SET data = EXCLUDED.data
                """.trimIndent(),
                currentServerId,
                archivedId,
            )

            val snapshot = loader.buildSnapshot()
            assertEquals(currentServerId, snapshot.serverId)
            assertTrue(snapshot.archivedNationIds.contains(archivedId))
            val restarted = InMemoryTurnWorld(snapshot)
            assertEquals(archivedId + 1, restarted.allocateNationId())
        } finally {
            jdbc.update("DELETE FROM ng_old_nations WHERE server_id = ? AND nation = ?", currentServerId, archivedId)
            jdbc.update("DELETE FROM ng_games WHERE server_id = 'newest-wrong-server'")
        }
    }

    private fun count(table: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java) ?: 0

    private fun countWhere(table: String, predicate: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $predicate", Int::class.java) ?: 0
}
