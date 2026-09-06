package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.*
import opensamguk.logic.world.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real PostgreSQL transaction + actual boot loader. Docker absence is a skip, never success evidence. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaterControlPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var executor: JdbcFlushExecutor
    private val topology = StrategicTopologySnapshot("r1", emptySet(), listOf("lake", "coast").map {
        WaterZoneRecord(it, WaterZoneKind.LAKE_BASIN, "geometry:$it", listOf("reviewed:$it"),
            EvidenceConfidence.REVIEWED, seasonalAvailability = SeasonalAvailability.ALWAYS)
    }, emptyList(), emptyList(), mapOf("fixture" to "sha"))

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — water control PostgreSQL persistence and V48→V49 integration NOT verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        jdbc = JdbcTemplate(dataSource)
        migrate("48")
        seedWorld(1, "han-world-v2")
        seedWorld(2, "han-world-v3")
        migrate("49")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM water_zone_control", Int::class.java))
        assertEquals(listOf("han-world-v2", "han-world-v3"), jdbc.query(
            "SELECT config ->> 'mapName' FROM world_state ORDER BY id", { rs, _ -> rs.getString(1) }))
        migrate("50")
        // V50 뒤 스키마(예: V55 general_retainers/general_bugok)를 WorldSnapshotLoader 가 읽으므로 cold boot 전에 최신까지 올린다.
        migrateLatest()
        executor = JdbcFlushExecutor(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(DataSourceTransactionManager(dataSource)))
    }

    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    private fun migrateLatest() {
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
    }

    private fun migrate(target: String) {
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration").target(target)
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
    }

    private fun seedWorld(id: Int, map: String = "han-world-v3") {
        jdbc.update("INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, config, world_version, writer_epoch) " +
            "VALUES (?, 'water-test', 200, 1, 60, CAST(? AS jsonb), 0, 1)", id, "{\"mapName\":\"$map\"}")
    }

    private fun load(id: Int) = WorldSnapshotLoader(jdbc, SeedBootstrap(seedEnabled = false, worldId = WorldId(id)),
        WorldId(id), snapshotValidator = {}, waterTopologyLoader = { topology }).buildSnapshot()

    private fun state(zone: String = "lake", revision: Long = 1, hash: String = topology.contentHash) =
        WaterControlState(topology.topologyRevision, hash, zone, 3L, emptyList(), WaterBlockadeState.OPEN, revision)

    private fun payload(id: Int, writes: List<WaterControlWriteRow>, worldVersion: Long = 0) =
        FlushPayload(WorldId(id), mapOf("id" to id, "current_year" to 200, "current_month" to 2,
            "expected_world_version" to worldVersion, "writer_epoch" to 1L), waterControlWrites = WaterControlWriteBatch(writes))

    @Test fun `baseline V49 creates no ownership for existing legacy or V3 worlds`() {
        assertNull(load(1).waterControlSnapshot)
        assertTrue(load(2).waterControlSnapshot!!.statesByZoneId.isEmpty())
    }

    @Test fun `coalesced recorder flush roundtrips through PostgreSQL and actual cold boot`() {
        seedWorld(10)
        seedWorld(11)
        val world = InMemoryTurnWorld(load(10))
        val recorder = ChangeRecorder()
        recorder.applyWaterControlAssessment(world, null, WaterControlAssessment("r1", topology.contentHash,
            "lake", 3L, emptyList(), WaterBlockadeState.OPEN))
        recorder.applyWaterControlAssessment(world, 1, WaterControlAssessment("r1", topology.contentHash,
            "lake", null, listOf(9, 5), WaterBlockadeState.CONTESTED))
        val batch = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        executor.flush(batch.copy(worldStateUpdate = batch.worldStateUpdate +
            mapOf("expected_world_version" to 0L, "writer_epoch" to 1L)))
        assertEquals(world.waterControlSnapshot()!!.stateFor("lake"), load(10).waterControlSnapshot!!.stateFor("lake"))
        assertTrue(load(11).waterControlSnapshot!!.statesByZoneId.isEmpty())
        assertEquals(1L, jdbc.queryForObject("SELECT world_version FROM world_state WHERE id = 10", Long::class.java))
    }

    @Test fun `one stale water row rolls back preceding water changes and world fence atomically`() {
        seedWorld(20)
        executor.flush(payload(20, listOf(WaterControlWriteRow(null, state()), WaterControlWriteRow(null, state("coast")))))
        assertFailsWith<StaleWaterControlException> {
            executor.flush(payload(20, listOf(WaterControlWriteRow(1, state(revision = 2)),
                WaterControlWriteRow(9, state("coast", revision = 10))), worldVersion = 1))
        }
        assertEquals(1L, jdbc.queryForObject("SELECT world_version FROM world_state WHERE id = 20", Long::class.java))
        assertEquals(listOf(1L, 1L), jdbc.query("SELECT revision FROM water_zone_control WHERE world_id = 20 ORDER BY water_zone_id",
            { rs, _ -> rs.getLong(1) }))
    }

    @Test fun `same zone belongs to separate worlds and duplicate insert or stale pins never overwrite`() {
        seedWorld(30)
        seedWorld(31)
        executor.flush(payload(30, listOf(WaterControlWriteRow(null, state()))))
        executor.flush(payload(31, listOf(WaterControlWriteRow(null, state(revision = 2)))))
        assertFailsWith<StaleWaterControlException> {
            executor.flush(payload(30, listOf(WaterControlWriteRow(null, state(revision = 3))), worldVersion = 1))
        }
        assertFailsWith<StaleWaterControlException> {
            executor.flush(payload(30, listOf(WaterControlWriteRow(1, state(revision = 3, hash = "b".repeat(64)))), worldVersion = 1))
        }
        assertEquals(1L, load(30).waterControlSnapshot!!.stateFor("lake")!!.revision)
        assertEquals(2L, load(31).waterControlSnapshot!!.stateFor("lake")!!.revision)
    }

    @Test fun `corrupt persisted topology is rejected by actual cold boot`() {
        seedWorld(40)
        executor.flush(payload(40, listOf(WaterControlWriteRow(null, state(hash = "b".repeat(64))))))
        assertFailsWith<IllegalArgumentException> { load(40) }
    }
}
