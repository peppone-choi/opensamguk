package opensamguk.engine.boot

import kotlin.test.*
import opensamguk.common.wire.TurnDaemonCommand.ImmediateInput
import opensamguk.engine.campaign.*
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.domestic.CountyWorks
import opensamguk.logic.domestic.DomesticWork
import opensamguk.logic.domestic.WorkReductionState
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.Phase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** CI checks the actual work producer and the accepted/applied reduction across database reloads. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkReductionPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: EnlistmentFixture

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable)
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)))
        fixture = EnlistmentFixture(jdbc, flush)
    }

    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    private fun save(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))

    private fun cold(id: Int, now: Phase) = InMemoryTurnWorld(fixture.load(id)).also {
        it.setCurrentDate(now.year, now.month, now.phase)
    }

    @Test fun `completed work queued reduction and replay guard persist through actual postgres reloads`() {
        val id = 733
        fixture.seed(id)
        var now = Phase(200, 1, 1)
        var world = cold(id, now)
        val county = world.administrativeCountyIds.sorted().first { world.landNodeOfCity(it) != null }
        jdbc.update("UPDATE general SET user_id='42' WHERE world_id=? AND id=10", id)
        jdbc.update("UPDATE city SET nation_id=1, meta=?::jsonb WHERE world_id=? AND id=?", MetaJson.encode(mapOf(
            CountyWarehouse.META_KEY to CountyWarehouse(county, 0,
                Resources(1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000)).toMetaValue())), id, county)
        world = cold(id, now)
        val context = DomesticContext()
        var recorder = ChangeRecorder()
        val body = """{"countyId":$county,"work":"FORTIFICATION"}"""
        assertTrue(CourtHandler(world, recorder, context).handle(ImmediateInput("build-fort", 10, 42, "work.start", body)).ok)
        save(world, recorder)
        world = cold(id, now)
        assertNotNull(CountyWorks.read(world.getCityById(county)!!.meta)!!.active)
        for (i in 1..100) {
            now = now.plus(1); world.setCurrentDate(now.year, now.month, now.phase)
            recorder = ChangeRecorder()
            DomesticBoundary(world, recorder, context).run()
            save(world, recorder); world = cold(id, now)
            if (CountyWorks.read(world.getCityById(county)!!.meta)!!.active == null) break
        }
        val built = world.getCityById(county)!!
        assertEquals(listOf(DomesticWork.FORTIFICATION), CountyWorks.read(built.meta)!!.completed.map { it.work })
        val stock = CountyWarehouse.read(built.meta, county)!!.stock
        recorder = ChangeRecorder()
        val command = ImmediateInput("reduce-fort", 10, 42, "work.reduce", body)
        val intake = CourtHandler(world, recorder, context).handle(command)
        assertTrue(intake.ok); assertEquals("reservationAccepted", intake.type)
        save(world, recorder); world = cold(id, now)
        val waiting = world.getCityById(county)!!
        assertEquals(built.defence, waiting.defence); assertEquals(built.wall, waiting.wall)
        assertEquals(stock, CountyWarehouse.read(waiting.meta, county)!!.stock)
        assertEquals("reduce-fort", WorkReductionState.read(waiting.meta)!!.pending!!.requestId)
        assertEquals("UNCHANGED", CourtHandler(world, ChangeRecorder(), context).handle(command).code)
        assertTrue(DomesticBoundary(world, ChangeRecorder(), context).run()!!.alreadyStamped)
        now = now.plus(1); world.setCurrentDate(now.year, now.month, now.phase)
        recorder = ChangeRecorder()
        assertFalse(DomesticBoundary(world, recorder, context).run()!!.alreadyStamped)
        save(world, recorder); world = cold(id, now)
        val reduced = world.getCityById(county)!!
        assertEquals((built.defence - 500).coerceAtLeast(0), reduced.defence)
        assertEquals((built.wall - 500).coerceAtLeast(0), reduced.wall)
        assertTrue(CountyWorks.read(reduced.meta)!!.completed.isEmpty())
        assertEquals(stock, CountyWarehouse.read(reduced.meta, county)!!.stock)
        val state = assertNotNull(WorkReductionState.read(reduced.meta))
        assertNull(state.pending); assertNull(state.last!!.reason)
        assertEquals(setOf("reduce-fort"), state.processedRequestIds)
        assertEquals(reduced.defence, jdbc.queryForObject("SELECT def FROM city WHERE world_id=? AND id=?",
            Int::class.java, id, county))
        assertEquals(reduced.wall, jdbc.queryForObject("SELECT wall FROM city WHERE world_id=? AND id=?",
            Int::class.java, id, county))
        assertEquals("UNCHANGED", CourtHandler(world, ChangeRecorder(), context).handle(command).code)
        assertTrue(DomesticBoundary(world, ChangeRecorder(), context).run()!!.alreadyStamped)
        assertEquals(reduced, world.getCityById(county))
    }
}
