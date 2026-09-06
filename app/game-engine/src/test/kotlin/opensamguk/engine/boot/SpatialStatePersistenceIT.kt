package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.*
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.read.SpatialStateReadRepository
import opensamguk.infra.persistence.*
import opensamguk.logic.world.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.AbstractDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource
import kotlin.test.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpatialStatePersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var named: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor
    private lateinit var source: DataSource
    private val topology = StrategicTopologySnapshot("r1", setOf("p1", "p2"), listOf(
        WaterZoneRecord("lake", WaterZoneKind.LAKE_BASIN, "geometry:lake", listOf("reviewed:lake"),
            EvidenceConfidence.REVIEWED, seasonalAvailability = SeasonalAvailability.ALWAYS),
    ), emptyList(), emptyList(), mapOf("fixture" to "sha"))

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — spatial PostgreSQL roundtrip NOT verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        jdbc = JdbcTemplate(source)
        named = NamedParameterJdbcTemplate(source)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        executor = JdbcFlushExecutor(named, TransactionTemplate(DataSourceTransactionManager(source)))
    }

    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    private fun seed(id: Int, map: String = "han-world-v3") {
        jdbc.update("INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, config, world_version, writer_epoch) " +
            "VALUES (?, 'spatial-test', 200, 1, 60, CAST(? AS jsonb), 0, 1)", id, "{\"mapName\":\"$map\"}")
        jdbc.update("INSERT INTO general (world_id, id, name, turn_time) VALUES (?, 7, 'G', now())", id)
        jdbc.update("INSERT INTO ng_games (world_id, server_id, date, season, scenario, scenario_name, env) " +
            "VALUES (?, ?, now(), 1, 0, 'Spatial test', '{}'::jsonb)", id, "spatial-test-$id")
    }

    private fun load(id: Int) = WorldSnapshotLoader(jdbc, SeedBootstrap(seedEnabled = false, worldId = WorldId(id)),
        WorldId(id), snapshotValidator = {}, waterTopologyLoader = { topology }).buildSnapshot()
    private fun read(id: Int) = SpatialStateReadRepository(named, GameApiProcessWorld(id)).readSnapshot(id, topology)
    private fun province(owner: Int, id: String = "p1") = ProvinceControlAssessment("r1", topology.contentHash, id, owner)
    private fun position(water: Boolean, generalId: Int = 7) = GeneralPositionAssessment("r1", topology.contentHash, generalId,
        if (water) StrategicNodeRef.WaterZone("lake") else StrategicNodeRef.LandProvince("p1"))
    private fun flush(world: InMemoryTurnWorld, recorder: ChangeRecorder): FlushPayload {
        val payload = payload(world, recorder)
        executor.flush(payload)
        world.advanceWorldVersionAfterCommit()
        recorder.clear()
        return payload
    }
    private fun payload(world: InMemoryTurnWorld, recorder: ChangeRecorder): FlushPayload {
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        return payload.copy(worldStateUpdate = payload.worldStateUpdate + mapOf(
            "expected_world_version" to world.getState().worldVersion,
            "writer_epoch" to world.getState().writerEpoch,
        ))
    }

    @Test fun `actual recorder flush coldboot and API preserve both channels and world isolation`() {
        seed(10); seed(11)
        for (id in listOf(10, 11)) {
            val world = InMemoryTurnWorld(load(id))
            val recorder = ChangeRecorder()
            recorder.applyProvinceControlAssessment(world, null, province(id))
            recorder.applyProvinceControlAssessment(world, 1, province(id + 1))
            recorder.applyProvinceControlAssessment(world, null, province(0, "p2"))
            recorder.applyGeneralPositionAssessment(world, null, position(false))
            recorder.applyGeneralPositionAssessment(world, 1, position(true))
            val payload = flush(world, recorder)
            assertNull(payload.provinceControlWrites.first().expectedRevision)
            assertNull(payload.generalPositionWrites.single().expectedRevision)
            val boot = load(id)
            val api = read(id)
            assertEquals(id + 1, boot.provinceControlSnapshot!!.stateFor("p1")!!.nationId)
            assertEquals(0, api.provinceControlSnapshot.stateFor("p2")!!.nationId)
            assertEquals(listOf("p1", "p2"), api.provinceControlSnapshot.statesByProvinceId.keys.toList())
            assertEquals(2L, boot.generalPositionSnapshot!!.stateFor(7)!!.revision)
            assertEquals(StrategicNodeRef.WaterZone("lake"), api.generalPositionSnapshot.stateFor(7)!!.node)
            assertEquals(boot.generalPositionSnapshot!!.statesByGeneralId, api.generalPositionSnapshot.statesByGeneralId)
            assertEquals(1L, jdbc.queryForObject("SELECT world_version FROM world_state WHERE id = ?", Long::class.java, id))
        }
        assertEquals(11, read(10).provinceControlSnapshot.stateFor("p1")!!.nationId)
        assertEquals(12, read(11).provinceControlSnapshot.stateFor("p1")!!.nationId)
    }

    @Test fun `stale position rolls back recorder province write and fence without mutating retry payload`() {
        seed(20)
        val world = InMemoryTurnWorld(load(20))
        val recorder = ChangeRecorder()
        recorder.applyProvinceControlAssessment(world, null, province(1))
        recorder.applyGeneralPositionAssessment(world, null, position(false))
        flush(world, recorder)
        recorder.applyProvinceControlAssessment(world, 1, province(2))
        recorder.applyGeneralPositionAssessment(world, 1, position(true))
        val payload = payload(world, recorder)
        jdbc.update("UPDATE general_spatial_position SET revision = 8 WHERE world_id = 20")
        assertFailsWith<StaleGeneralPositionException> { executor.flush(payload) }
        assertEquals(1, read(20).provinceControlSnapshot.stateFor("p1")!!.nationId)
        assertEquals(1L, jdbc.queryForObject("SELECT world_version FROM world_state WHERE id = 20", Long::class.java))
        recorder.clear()
        assertEquals(1L, payload.generalPositionWrites.single().expectedRevision)
        assertEquals(2L, payload.generalPositionWrites.single().state.revision)
        assertFailsWith<UnsupportedOperationException> { (payload.provinceControlWrites as MutableList<*>).clear() }
    }

    @Test fun `new general position inserts after core row and deletion only cascades in selected world`() {
        seed(30); seed(31)
        for (id in listOf(30, 31)) {
            val world = InMemoryTurnWorld(load(id))
            val recorder = ChangeRecorder()
            recorder.recordGeneralCreate(world, world.getGeneralById(7)!!.copy(id = 8))
            recorder.applyGeneralPositionAssessment(world, null, position(true, 8))
            flush(world, recorder)
            assertNotNull(load(id).generalPositionSnapshot!!.stateFor(8))
        }
        val world = InMemoryTurnWorld(load(30))
        val recorder = ChangeRecorder()
        recorder.applyGeneralPositionAssessment(world, 1, position(false, 8))
        recorder.markGeneralDeleted(world, 8)
        recorder.recordGeneralCreate(world, world.getGeneralById(7)!!.copy(id = 9))
        recorder.applyGeneralPositionAssessment(world, null, position(false, 9))
        recorder.recordAccessLogUpsert(world, GeneralAccessLog(9, refresh = 3))
        recorder.markGeneralDeleted(world, 9)
        val payload = flush(world, recorder)
        assertTrue(payload.generalPositionWrites.isEmpty())
        assertNull(load(30).generalPositionSnapshot!!.stateFor(8))
        assertNull(load(30).generalPositionSnapshot!!.stateFor(9))
        assertNotNull(read(31).generalPositionSnapshot.stateFor(8))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general WHERE world_id = 30 AND id IN (8,9)", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ng_old_generals WHERE world_id = 30 AND general_no = 9", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ng_old_generals WHERE world_id = 30 AND general_no = 8", Int::class.java))
        assertEquals(listOf(8), payload.generalOwnerDeletes)
        assertEquals(listOf(8), payload.generalAccessLogDeletes)
        assertTrue(payload.generalAccessLogUpserts.isEmpty())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general_access_log WHERE world_id = 30 AND general_id = 9", Int::class.java))
    }

    @Test fun `empty V3 is unknown legacy has no snapshots and corrupt pins or nodes fail closed`() {
        seed(40); seed(41, "han-world-v2")
        assertTrue(load(40).provinceControlSnapshot!!.statesByProvinceId.isEmpty())
        assertTrue(read(40).generalPositionSnapshot.statesByGeneralId.isEmpty())
        assertNull(load(41).provinceControlSnapshot)
        assertNull(load(41).generalPositionSnapshot)
        val world = InMemoryTurnWorld(load(40))
        val recorder = ChangeRecorder()
        recorder.applyProvinceControlAssessment(world, null, province(1))
        recorder.applyGeneralPositionAssessment(world, null, position(false))
        flush(world, recorder)
        jdbc.update("UPDATE province_control SET topology_hash = ? WHERE world_id = 40", "b".repeat(64))
        assertFailsWith<IllegalArgumentException> { load(40) }
        assertFailsWith<IllegalArgumentException> { read(40) }
        jdbc.update("UPDATE province_control SET topology_hash = ? WHERE world_id = 40", topology.contentHash)
        jdbc.update("UPDATE general_spatial_position SET node_id = 'missing' WHERE world_id = 40")
        assertFailsWith<IllegalArgumentException> { load(40) }
        assertFailsWith<IllegalArgumentException> { read(40) }
    }

    @Test fun `API combined snapshot cannot mix versions when a flush commits during row consumption`() {
        seed(50)
        val world = InMemoryTurnWorld(load(50))
        val recorder = ChangeRecorder()
        recorder.applyProvinceControlAssessment(world, null, province(1))
        recorder.applyGeneralPositionAssessment(world, null, position(false))
        flush(world, recorder)
        var committed = false
        val readSource = rowConsumptionSource {
            if (!committed) {
                committed = true
                recorder.applyProvinceControlAssessment(world, 1, province(2))
                recorder.applyGeneralPositionAssessment(world, 1, position(true))
                flush(world, recorder)
            }
        }
        val before = SpatialStateReadRepository(NamedParameterJdbcTemplate(readSource), GameApiProcessWorld(50))
            .readSnapshot(50, topology)
        assertTrue(committed)
        assertEquals(1, before.provinceControlSnapshot.stateFor("p1")!!.nationId)
        assertEquals(StrategicNodeRef.LandProvince("p1"), before.generalPositionSnapshot.stateFor(7)!!.node)
        val after = read(50)
        assertEquals(2, after.provinceControlSnapshot.stateFor("p1")!!.nationId)
        assertEquals(StrategicNodeRef.WaterZone("lake"), after.generalPositionSnapshot.stateFor(7)!!.node)
        assertEquals(1L, before.generalPositionSnapshot.stateFor(7)!!.revision)
    }

    /** Decorates real JDBC only to place a real committing writer between result consumption steps. */
    private fun rowConsumptionSource(afterRow: () -> Unit): DataSource = object : AbstractDataSource() {
        override fun getConnection(): Connection = decorate(Connection::class.java, source.connection) { name, value ->
            if (name != "prepareStatement") value else decorate(PreparedStatement::class.java, value as PreparedStatement) { method, result ->
                if (method != "executeQuery") result else decorate(ResultSet::class.java, result as ResultSet) { call, row ->
                    if (call == "next" && row == true) afterRow()
                    row
                }
            }
        }
        override fun getConnection(username: String, password: String): Connection = getConnection()
    }

    private fun <T : Any> decorate(type: Class<T>, delegate: T, after: (String, Any?) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            val result = try { method.invoke(delegate, *args.orEmpty()) }
            catch (error: InvocationTargetException) { throw error.targetException }
            after(method.name, result)
        },
    )
}
