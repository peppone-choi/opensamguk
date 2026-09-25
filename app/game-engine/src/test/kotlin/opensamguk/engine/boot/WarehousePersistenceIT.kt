package opensamguk.engine.boot

import kotlin.test.*
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.hwiha.WarehouseSettlement
import opensamguk.engine.hwiha.WarehouseSettlement.Result.*
import opensamguk.engine.turn.*
import opensamguk.logic.economy.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.MetaJson
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehousePersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: EnlistmentFixture
    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable)
        postgres=PostgreSQLContainer("postgres:16-alpine").apply { start() }
        val source=DriverManagerDataSource(postgres.jdbcUrl,postgres.username,postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc=JdbcTemplate(source)
        flush=JdbcFlushExecutor(NamedParameterJdbcTemplate(source),TransactionTemplate(DataSourceTransactionManager(source)))
        fixture=EnlistmentFixture(jdbc,flush)
    }
    @AfterAll fun teardown() { if(this::postgres.isInitialized) postgres.stop() }
    private fun save(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        flush.flush(DatabaseHooks.toFlushPayload(world,recorder,world.consumeDirtyState()))
    private fun cold(id: Int) = InMemoryTurnWorld(fixture.load(id))
    @Test fun `five resource settlement flush reload retries ownership and rollback preserve stock`() {
        fixture.seed(701); fixture.seed(702)
        var world = cold(701)
        val county = world.administrativeCountyIds.min()
        val stock = Resources(100, 200, 300, 400, 500)
        val initial = CountyWarehouse(county, 0, stock)
        fun stored(w: InMemoryTurnWorld) = CountyWarehouse.read(w.getCityById(county)!!.meta, county)
        var recorder = ChangeRecorder()
        assertEquals(NOT_READY, WarehouseSettlement(world, recorder).settle(county, 0, 0, Resources()))
        // Explicit scenario fixture inventory, never inferred from national or personal balances.
        jdbc.update("UPDATE city SET meta=?::jsonb WHERE world_id=701 AND id=?",
            MetaJson.encode(mapOf("keep" to 17, CountyWarehouse.META_KEY to initial.toMetaValue())), county)
        world = cold(701)
        val original = world.getCityById(county)!!
        val people = world.listGenerals()
        val cost = Resources(1, 2, 3, 4, 5)
        recorder = ChangeRecorder()
        val settlement = WarehouseSettlement(world, recorder)
        assertEquals(INSUFFICIENT_STOCK, settlement.settle(county, 0, 0, Resources(horses=501), stock))
        assertEquals(original, world.getCityById(county))
        assertEquals(OWNER_CHANGED, settlement.settle(county, 1, 0, cost))
        assertEquals(APPLIED, settlement.settle(county, 0, 0, cost))
        save(world, recorder)
        world = cold(701)
        assertEquals(initial.replace(stock.debit(cost)!!), stored(world))
        assertEquals(17, world.getCityById(county)!!.meta["keep"])
        assertEquals(people, world.listGenerals())
        assertNull(stored(cold(702)))
        assertEquals(STALE_REVISION, WarehouseSettlement(world, ChangeRecorder()).settle(county, 0, 0, cost))
        jdbc.update("UPDATE city SET nation_id=1 WHERE world_id=701 AND id=?", county)
        world = cold(701)
        assertEquals(OWNER_CHANGED, WarehouseSettlement(world, ChangeRecorder()).settle(county, 0, 1, cost))
        val committed = stored(world)
        recorder = ChangeRecorder()
        assertEquals(APPLIED, WarehouseSettlement(world, recorder).settle(county, 1, 1, cost))
        val transaction = TransactionTemplate(DataSourceTransactionManager(checkNotNull(jdbc.dataSource)))
        assertFailsWith<IllegalStateException> {
            transaction.executeWithoutResult { save(world, recorder); error("rollback fixture") }
        }
        world = cold(701)
        assertEquals(committed, stored(world))
        recorder = ChangeRecorder()
        assertEquals(APPLIED, WarehouseSettlement(world, recorder).settle(county, 1, 1, cost))
        save(world, recorder)
        assertEquals(2L, stored(cold(701))!!.revision)
        jdbc.update("UPDATE city SET meta=jsonb_set(meta,'{hwihaCountyWarehouse,stock,grain}','-1') WHERE world_id=701 AND id=?", county)
        world = cold(701)
        assertEquals(INVALID_STATE, WarehouseSettlement(world, ChangeRecorder()).settle(county, 1, 2, cost))
    }
}
