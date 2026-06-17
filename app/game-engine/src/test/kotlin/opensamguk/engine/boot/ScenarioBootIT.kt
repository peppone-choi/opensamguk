package opensamguk.engine.boot

import opensamguk.common.constants.EffectiveGameConst
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.infra.persistence.ReservedTurnRepository
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
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.temporal.ChronoUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F1b boot/tick gate — proves the FULL fresh-DB → playable-world path end-to-end:
 *  1. fresh Postgres + Flyway baseline,
 *  2. [SeedBootstrap.ensureSeeded] seeds `scenario_1010` (678 generals / 24 cities / 2 nations),
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
        assertEquals(678, count("general"))
        assertEquals(94, count("city")) // che 풀맵: 점유 24 + 공백지 70 = 94 (cities_1010.json)
        assertEquals(2, count("nation"))

        // 3. load snapshot → 4. build the in-memory world
        val snapshot = loader.buildSnapshot()
        assertEquals(678, snapshot.generals.size)
        assertEquals(94, snapshot.cities.size) // che 풀맵: 점유 24 + 공백지 70 = 94
        assertEquals(2, snapshot.nations.size)
        assertEquals(0, snapshot.troops.size, "no troops at scenario start")
        assertEquals(2, snapshot.diplomacy.size)
        val expectedKillturn = EffectiveGameConst.killturn(snapshot.state.tickSeconds / 60, npcmode = 0)
        assertTrue(
            snapshot.generals.all { (it.meta["killturn"] as? Number)?.toInt() == expectedKillturn },
            "seeded generals load with PHP reset killturn baseline",
        )
        assertTrue(
            snapshot.generals.all { (it.meta["deadyear"] as? Number)?.toInt() ?: 0 > snapshot.state.currentYear },
            "seeded generals load with PHP deadyear lifecycle meta",
        )

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
        assertEquals(678, count("general"), "no duplicate generals after second seed")
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

    private fun count(table: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java) ?: 0
}
