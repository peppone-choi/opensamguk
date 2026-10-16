package opensamguk.engine.campaign

import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.boot.EnlistmentFixture
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.domestic.BarracksProduction
import opensamguk.logic.domestic.BarracksProduction.Code
import opensamguk.logic.domestic.BarracksProduction.Params
import opensamguk.logic.domestic.BarracksProduction.Queue
import opensamguk.logic.domestic.BarracksProduction.Recipe
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.Phase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

class BarracksProductionBoundaryTest {
    private val now = Phase(200, 1, 1)
    private val stock = Resources(100, 100, 100, 100, 100)
    private val cost = Resources(2, 3, 4, 5, 6)
    private val params = Params("CONFIRMED", { Recipe(cost, 1, 3) }, { 1 }, { 0 })
    private fun queue(county: Int = 10) = Queue(county, 1, orders = listOf(
        BarracksProduction.Order("order", "infantry", county, 2, now)))
    private fun city(id: Int = 10, nation: Int = 1, population: Int = 100,
        warehouse: CountyWarehouse = CountyWarehouse(id, 0, stock), q: Queue = queue(id)) =
        City(id, "county$id", nation, 1, population = population, populationMax = 1000,
            meta = mapOf("keep" to 17, CountyWarehouse.META_KEY to warehouse.toMetaValue(),
                BarracksProduction.META_KEY to q.toMetaValue()))
    private fun world(cities: List<City> = listOf(city())) = InMemoryTurnWorld(WorldSnapshot(
        worldId = WorldId(1), state = TurnWorldState(1, 200, 1, 3600, Instant.EPOCH, currentPhase = 2,
            config = mapOf("ruleProfile" to "HWIHA", "mapName" to "han-world-v3")),
        generals = emptyList(), nations = emptyList(), cities = cities,
        administrativeCountyIds = cities.map { it.id }.toSet()))
    private fun stored(world: InMemoryTurnWorld, id: Int = 10) =
        assertNotNull(BarracksProduction.read(world.getCityById(id)!!.meta, id))
    private fun warehouse(world: InMemoryTurnWorld, id: Int = 10) =
        assertNotNull(CountyWarehouse.read(world.getCityById(id)!!.meta, id))

    @Test fun `boundary commits warehouse revision population and queue together and retries do not spend`() {
        val world = world()
        val recorder = ChangeRecorder()
        val boundary = BarracksProductionBoundary(world, recorder, params)
        assertEquals(Code.APPLIED, boundary.run()[10])
        assertEquals(99, world.getCityById(10)!!.population)
        assertEquals(stock.debit(cost), warehouse(world).stock)
        assertEquals(1L, warehouse(world).revision)
        assertEquals(1, stored(world).orders.single().reserved!!.progress)
        assertEquals(17, world.getCityById(10)!!.meta["keep"])
        val after = world.getCityById(10)
        assertEquals(Code.NOT_DUE, boundary.run()[10])
        assertEquals(after, world.getCityById(10))
        world.setCurrentDate(200, 1, 3)
        assertEquals(Code.APPLIED, boundary.run()[10])
        assertEquals(99, world.getCityById(10)!!.population)
        assertEquals(stock.debit(cost), warehouse(world).stock)
        assertTrue(recorder.dirtyCityIds().isNotEmpty())
    }

    @Test fun `default product path is NOT_DECIDED and legacy metadata is untouched`() {
        val original = city()
        val legacy = city(11).copy(meta = mapOf("keep" to 8))
        val world = world(listOf(original, legacy))
        assertEquals(mapOf(10 to Code.NOT_DECIDED), BarracksProductionBoundary(world, ChangeRecorder()).run())
        assertEquals(100, world.getCityById(10)!!.population)
        assertEquals(stock, warehouse(world).stock)
        assertEquals(0L, warehouse(world).revision)
        assertEquals(legacy, world.getCityById(11))
        assertEquals(Code.NOT_DECIDED, stored(world).stopReason)
    }

    @Test fun `partial cancellation credits through WarehouseSettlement and never refunds twice`() {
        val world = world()
        val recorder = ChangeRecorder()
        val boundary = BarracksProductionBoundary(world, recorder, params)
        boundary.run()
        val q = stored(world)
        val revision = warehouse(world).revision
        assertEquals(Code.APPLIED, boundary.cancel(10, 1, "cancel", "order", q.revision, revision))
        assertEquals(stock, warehouse(world).stock)
        assertEquals(100, world.getCityById(10)!!.population)
        val done = world.getCityById(10)
        assertEquals(Code.ALREADY_APPLIED, boundary.cancel(10, 1, "cancel", "order", stored(world).revision,
            warehouse(world).revision))
        assertEquals(done, world.getCityById(10))
    }

    @Test fun `stale warehouse cancellation unknown cancel turn and credit overflow have no side effects`() {
        val world = world()
        BarracksProductionBoundary(world, ChangeRecorder(), params).run()
        val original = world.getCityById(10)
        val revision = stored(world).revision
        assertEquals(Code.INVALID_STATE, BarracksProductionBoundary(world, ChangeRecorder(), params)
            .cancel(10, 1, "cancel", "order", revision, 0))
        assertEquals(original, world.getCityById(10))
        assertEquals(Code.NOT_DECIDED, BarracksProductionBoundary(world, ChangeRecorder(), params.copy(turnCostPolicy = null))
            .cancel(10, 1, "cancel", "order", revision, warehouse(world).revision))
        assertEquals(original, world.getCityById(10))
        val overflowCity = original!!.copy(meta = original.meta + (CountyWarehouse.META_KEY to
            CountyWarehouse(10, 1, Resources(money = Long.MAX_VALUE)).toMetaValue()))
        val overflow = world(listOf(overflowCity))
        assertEquals(Code.OVERFLOW, BarracksProductionBoundary(overflow, ChangeRecorder(), params)
            .cancel(10, 1, "cancel", "order", revision, 1))
        assertEquals(overflowCity, overflow.getCityById(10))
    }

    @Test fun `missing original refund ledger is NOT_DECIDED without recreating a warehouse`() {
        val world = world()
        BarracksProductionBoundary(world, ChangeRecorder(), params).run()
        val partial = world.getCityById(10)!!
        val missing = partial.copy(meta = partial.meta - CountyWarehouse.META_KEY)
        world.applyCityDirtyFree(missing)
        assertEquals(Code.NOT_DECIDED, BarracksProductionBoundary(world, ChangeRecorder(), params)
            .cancel(10, 1, "cancel", "order", stored(world).revision, 1))
        assertEquals(missing, world.getCityById(10))
    }

    @Test fun `ownership change freezes queue before touching either warehouse or population`() {
        val world = world()
        BarracksProductionBoundary(world, ChangeRecorder(), params).run()
        val before = world.getCityById(10)!!.copy(nationId = 2)
        world.applyCityDirtyFree(before)
        world.setCurrentDate(200, 1, 3)
        val boundary = BarracksProductionBoundary(world, ChangeRecorder(), params)
        assertEquals(Code.OWNER_CHANGED, boundary.run()[10])
        assertEquals(before.population, world.getCityById(10)!!.population)
        assertEquals(before.meta[CountyWarehouse.META_KEY], world.getCityById(10)!!.meta[CountyWarehouse.META_KEY])
        assertTrue(stored(world).frozen)
        assertEquals(Code.OWNER_CHANGED, boundary.cancel(10, 2, "cancel", "order", stored(world).revision,
            warehouse(world).revision))
    }

    @Test fun `bad county state does not prevent another county from progressing`() {
        val invalid = city().copy(meta = mapOf(BarracksProduction.META_KEY to "bad"))
        val world = world(listOf(invalid, city(11)))
        assertEquals(mapOf(10 to Code.INVALID_STATE, 11 to Code.APPLIED),
            BarracksProductionBoundary(world, ChangeRecorder(), params).run())
        assertEquals(invalid, world.getCityById(10))
        assertEquals(99, world.getCityById(11)!!.population)
    }

    @Test fun `real PostgreSQL flush cold reload retry cancellation and rollback preserve all three ledgers`() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable)
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { postgres ->
            postgres.start()
            val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
            val jdbc = JdbcTemplate(source)
            val transaction = TransactionTemplate(DataSourceTransactionManager(source))
            val flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), transaction)
            val fixture = EnlistmentFixture(jdbc, flush)
            fixture.seed(706)
            var world = InMemoryTurnWorld(fixture.load(706))
            val county = world.administrativeCountyIds.min()
            val initial = city(county).meta
            jdbc.update("UPDATE city SET nation_id=1, pop=100, meta=?::jsonb WHERE world_id=706 AND id=?",
                MetaJson.encode(initial), county)
            fun cold() = InMemoryTurnWorld(fixture.load(706))
            fun save(w: InMemoryTurnWorld, r: ChangeRecorder) =
                flush.flush(DatabaseHooks.toFlushPayload(w, r, w.consumeDirtyState()))
            world = cold()
            world.setCurrentDate(200, 1, 2)
            var recorder = ChangeRecorder()
            assertEquals(Code.APPLIED, BarracksProductionBoundary(world, recorder, params).run()[county])
            transaction.executeWithoutResult { save(world, recorder) }
            world = cold()
            world.setCurrentDate(200, 1, 2)
            val committed = world.getCityById(county)
            assertEquals(99, committed!!.population)
            assertEquals(stock.debit(cost), warehouse(world, county).stock)
            assertEquals(1, stored(world, county).orders.single().reserved!!.progress)
            assertEquals(Code.NOT_DUE, BarracksProductionBoundary(world, ChangeRecorder(), params).run()[county])
            assertEquals(committed, world.getCityById(county))
            recorder = ChangeRecorder()
            assertEquals(Code.APPLIED, BarracksProductionBoundary(world, recorder, params).cancel(county, 1,
                "cancel", "order", stored(world, county).revision, warehouse(world, county).revision))
            assertFailsWith<IllegalStateException> {
                transaction.executeWithoutResult { save(world, recorder); error("rollback production fixture") }
            }
            world = cold()
            assertEquals(committed, world.getCityById(county))
            recorder = ChangeRecorder()
            assertEquals(Code.APPLIED, BarracksProductionBoundary(world, recorder, params).cancel(county, 1,
                "cancel", "order", stored(world, county).revision, warehouse(world, county).revision))
            transaction.executeWithoutResult { save(world, recorder) }
            world = cold()
            assertEquals(100, world.getCityById(county)!!.population)
            assertEquals(stock, warehouse(world, county).stock)
            val refunded = world.getCityById(county)
            assertEquals(Code.ALREADY_APPLIED, BarracksProductionBoundary(world, ChangeRecorder(), params).cancel(county, 1,
                "cancel", "order", stored(world, county).revision, warehouse(world, county).revision))
            assertEquals(refunded, world.getCityById(county))
        }
    }
}
