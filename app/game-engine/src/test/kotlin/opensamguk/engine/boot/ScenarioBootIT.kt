package opensamguk.engine.boot

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
import org.junit.jupiter.api.TestInstance
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
    fun `seed then load then advance one turn green`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario boot IT skipped (not failed)")

        // 2. seed
        assertTrue(bootstrap.ensureSeeded(jdbc), "first ensureSeeded seeds the fresh world")
        assertEquals(678, count("general"))
        assertEquals(24, count("city"))
        assertEquals(2, count("nation"))

        // 3. load snapshot → 4. build the in-memory world
        val snapshot = loader.buildSnapshot()
        assertEquals(678, snapshot.generals.size)
        assertEquals(24, snapshot.cities.size)
        assertEquals(2, snapshot.nations.size)
        assertEquals(0, snapshot.troops.size, "no troops at scenario start")
        assertEquals(2, snapshot.diplomacy.size)

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

        // 5. second seed is a no-op (emptiness gate).
        assertTrue(!bootstrap.ensureSeeded(jdbc), "second ensureSeeded is a no-op")
        assertEquals(678, count("general"), "no duplicate generals after second seed")
    }

    private fun count(table: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java) ?: 0
}
