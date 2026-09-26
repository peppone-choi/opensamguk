package opensamguk.engine.boot

import opensamguk.common.wire.RetainerActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.intake.RetainerHandler
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.JdbcFlushExecutor
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExistingRetainerPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var executor: JdbcFlushExecutor
    private lateinit var fixture: EnlistmentFixture
    private val now = Instant.parse("0200-01-01T00:00:00Z")

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false), "Docker unavailable — retainer roundtrip unverified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        jdbc = JdbcTemplate(ds)
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        executor = JdbcFlushExecutor(NamedParameterJdbcTemplate(ds), TransactionTemplate(DataSourceTransactionManager(ds)))
        fixture = EnlistmentFixture(jdbc, executor)
        fixture.seed(1)
        jdbc.update("DELETE FROM general_bugok WHERE world_id=1")
        jdbc.update("DELETE FROM general_retainers WHERE world_id=1")
        jdbc.update("""UPDATE general SET name='주인', nation_id=0, npc_state=0, gold=5000,
            rice=5000, crew=400, meta=jsonb_set(meta,'{lord}','false') WHERE world_id=1 AND id=10""")
        jdbc.update("""INSERT INTO general(world_id,id,name,nation_id,city_id,npc_state,gold,rice,crew,turn_time)
            SELECT world_id,20,'장수',0,city_id,2,1000,1000,300,now() FROM general WHERE world_id=1 AND id=10""")
        jdbc.update("""UPDATE general SET leadership=70,strength=70,intel=70,politics=70,charm=70,
            meta=(SELECT jsonb_set(meta,'{personPolicy,officerId}','20') FROM general WHERE world_id=1 AND id=2)
            WHERE world_id=1 AND id=20""")
        jdbc.update("""INSERT INTO general_spatial_position(world_id,general_id,topology_revision,topology_hash,node_kind,node_id,revision)
            SELECT world_id,20,topology_revision,topology_hash,node_kind,node_id,1
            FROM general_spatial_position WHERE world_id=1 AND general_id=10""")
    }
    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }
    private fun load() = fixture.load(1)
    private fun flush(world: InMemoryTurnWorld, recorder: ChangeRecorder) = executor.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))

    @Test fun `lord metadata survives recorder flush and cold reload without replacing other fields`() {
        // Storage contract only: this fixture does not activate a HWIHA gameplay handler.
        val world = InMemoryTurnWorld(load())
        val before = world.getGeneralById(10)!!
        val marked = before.copy(meta = LinkedHashMap(before.meta).apply { put("lord", true) })
        val recorder = ChangeRecorder()
        world.applyGeneralDirtyFree(marked)
        recorder.diffGeneral(opensamguk.engine.turn.PerTurnOverlay.toLogicGeneral(before),
            opensamguk.engine.turn.PerTurnOverlay.toLogicGeneral(marked))
        flush(world, recorder)
        val rebooted = InMemoryTurnWorld(load())
        val loaded = rebooted.getGeneralById(10)!!
        assertEquals(marked, loaded)
        assertTrue(opensamguk.logic.input.LordStatus.read(loaded.meta))
        val released = loaded.copy(meta = opensamguk.logic.input.LordStatus.afterEnlistment(loaded.meta))
        val releaseRecorder = ChangeRecorder()
        rebooted.applyGeneralDirtyFree(released)
        releaseRecorder.diffGeneral(opensamguk.engine.turn.PerTurnOverlay.toLogicGeneral(loaded),
            opensamguk.engine.turn.PerTurnOverlay.toLogicGeneral(released))
        flush(rebooted, releaseRecorder)
        val after = InMemoryTurnWorld(load()).getGeneralById(10)!!
        assertEquals(released, after)
        assertEquals(false, opensamguk.logic.input.LordStatus.read(after.meta))
    }

    @Test fun `intake flush and cold boot preserve linked identity and released id high water`() {
        val world = InMemoryTurnWorld(load())
        val npc = world.getGeneralById(20)!!
        val recorder = ChangeRecorder()
        val handler = RetainerHandler(world, recorder, { now })
        val first = handler.handlePledge(TurnDaemonCommand.RetainerPledge(generalId = 10, targetGeneralId = 20, relation = "guest")) as RetainerActionResult
        assertTrue(first.ok, first.reason)
        flush(world, recorder)
        val rebooted = InMemoryTurnWorld(load())
        assertEquals(world.getRetainerById(first.id!!), rebooted.getRetainerById(first.id!!))
        assertEquals(npc, rebooted.getGeneralById(20))
        assertEquals(4500, rebooted.getGeneralById(10)!!.gold)
        val releaseRecorder = ChangeRecorder()
        RetainerHandler(rebooted, releaseRecorder, { now }).handleRelease(TurnDaemonCommand.RetainerRelease(generalId = 10, retainerId = first.id))
        flush(rebooted, releaseRecorder)
        val afterRelease = InMemoryTurnWorld(load())
        assertTrue(afterRelease.listRetainers().isEmpty())
        assertEquals(npc, afterRelease.getGeneralById(20))
        val second = RetainerHandler(afterRelease, ChangeRecorder(), { now }).handlePledge(TurnDaemonCommand.RetainerPledge(generalId = 10, targetGeneralId = 20, relation = "guest")) as RetainerActionResult
        assertTrue(second.ok)
        assertTrue(second.id!! > first.id!!)
    }
}
