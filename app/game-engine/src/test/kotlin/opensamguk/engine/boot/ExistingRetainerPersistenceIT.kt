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
        jdbc.update("INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'retainer-test', 200, 1, 3600)")
        jdbc.update("INSERT INTO general (world_id, id, name, nation_id, city_id, npc_state, gold, rice, crew, turn_time) VALUES (1, 10, '주인', 0, 1, 0, 5000, 5000, 400, now()), (1, 20, '장수', 0, 1, 2, 1000, 1000, 300, now())")
    }
    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }
    private fun load() = WorldSnapshotLoader(jdbc, SeedBootstrap(seedEnabled = false, worldId = WorldId(1)), WorldId(1), snapshotValidator = {}).buildSnapshot()
    private fun flush(world: InMemoryTurnWorld, recorder: ChangeRecorder) = executor.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))

    @Test fun `intake flush and cold boot preserve linked identity and released id high water`() {
        val world = InMemoryTurnWorld(load())
        val npc = world.getGeneralById(20)!!
        val recorder = ChangeRecorder()
        val handler = RetainerHandler(world, recorder, { now })
        val first = handler.handlePledge(TurnDaemonCommand.RetainerPledge(generalId = 10, targetGeneralId = 20, relation = "guest")) as RetainerActionResult
        assertTrue(first.ok)
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
