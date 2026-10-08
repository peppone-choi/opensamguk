package opensamguk.engine.boot

import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.campaign.*
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.*
import opensamguk.logic.economy.*
import opensamguk.logic.input.*
import opensamguk.logic.world.StrategicNodeRef
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.*
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DonationPersistenceIT {
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
    private fun cold(id: Int) = InMemoryTurnWorld(fixture.load(id))
    private fun save(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
    private fun seed(id: Int): Int {
        fixture.seed(id)
        val world = cold(id)
        val county = world.administrativeCountyIds.sorted().first { world.landNodeOfCity(it) is StrategicNodeRef.LandProvince }
        val node = (world.landNodeOfCity(county) as StrategicNodeRef.LandProvince).id
        jdbc.update("INSERT INTO nation(world_id,id,name,color,gold,rice) VALUES (?,2,'무영토','#222222',7,8)", id)
        jdbc.update("UPDATE general SET user_id='42',nation_id=2 WHERE world_id=? AND id=1", id)
        jdbc.update("UPDATE general SET nation_id=1,city_id=? WHERE world_id=? AND id IN (1,2,10)", county, id)
        jdbc.update("UPDATE general SET nation_id=2 WHERE world_id=? AND id=1", id)
        jdbc.update("UPDATE general_spatial_position SET node_id=? WHERE world_id=?", node, id)
        jdbc.update("UPDATE city SET nation_id=1,supply_state=1,meta=?::jsonb WHERE world_id=? AND id=?",
            MetaJson.encode(mapOf("keep" to 17, CountyWarehouse.META_KEY to CountyWarehouse(county, 0, Resources()).toMetaValue())), id, county)
        jdbc.update("UPDATE nation SET capital_city_id=? WHERE world_id=? AND id=1", county, id)
        jdbc.update("UPDATE general_retainers SET master_general_id=10 WHERE world_id=?", id)
        jdbc.update("UPDATE general_bugok SET master_general_id=10,provisions=0 WHERE world_id=?", id)
        return county
    }
    private fun reserve(id: Int, resource: String, amount: Int): ReservedTurnRepository.ReservedTurn {
        val named = NamedParameterJdbcTemplate(jdbc)
        val requestId = "donation-$id"
        val raw = """{"resource":"$resource","amount":$amount}"""
        val request = TransferInput.parse(1, TransferInput.DONATE, raw)!!
        val canonical = TransferInput.canonicalJson(request)
        val inbox = CommandInboxRepository(named)
        assertEquals(CommandInboxRepository.InsertResult.Inserted, inbox.insertAccepted(
            CommandInboxRepository.AcceptedCommand(WorldId(id), requestId,
                commandKind = CommandInboxRepository.CommandKind.RESERVED_TURN, intentFingerprint = requestId,
                generalId = 1, turnIdx = 0, actionCode = TransferInput.DONATE, payloadJson = "{}", ownerUserId = 42)))
        val repository = ReservedTurnRepository(named)
        repository.reserve(WorldId(id), 1, 0, TransferInput.DONATE, canonical, requestId = requestId)
        return repository.readReserved(WorldId(id), 1, 0).also {
            assertEquals(42, it.reservationOwnerUserId)
            assertEquals(request, TransferInput.parse(1, it.actionCode, it.argJson))
        }
    }
    private fun execute(world: InMemoryTurnWorld, recorder: ChangeRecorder, reserved: ReservedTurnRepository.ReservedTurn) =
        ReservedTurnHandler(world, EngineGeneralActionPipelineBuilder(world, 200).registryFor(world.getGeneralById(1)!!),
            "fixture-donation", 200,
            recorder = recorder).handle(1, reserved, 200, 1, "00:00").inputOutcome
    private fun stock(world: InMemoryTurnWorld, county: Int) = CountyWarehouse.read(world.getCityById(county)!!.meta, county)!!

    @Test fun `reserved donation atomically reloads both ledgers rolls back and funds actual salary`() {
        val id = 951; val county = seed(id); val reserved = reserve(id, "MONEY", 1000)
        var world = cold(id); var recorder = ChangeRecorder()
        assertIs<TurnOutcome.Applied>(execute(world, recorder, reserved))
        val tx = TransactionTemplate(DataSourceTransactionManager(checkNotNull(jdbc.dataSource)))
        assertFailsWith<IllegalStateException> { tx.executeWithoutResult { save(world, recorder); error("rollback") } }
        world = cold(id)
        assertEquals(1000, world.getGeneralById(1)!!.gold)
        assertEquals(0L, stock(world, county).stock.money)
        assertNull(world.getGeneralById(1)!!.meta["transferLastTurn"])
        recorder = ChangeRecorder()
        val applied = assertIs<TurnOutcome.Applied>(execute(world, recorder, reserved)); save(world, recorder)
        world = cold(id)
        assertEquals(0, world.getGeneralById(1)!!.gold)
        assertEquals(1000L, stock(world, county).stock.money)
        assertEquals(1L, stock(world, county).revision)
        assertEquals(17, world.getCityById(county)!!.meta["keep"])
        assertEquals(7, world.getNationById(2)!!.gold)
        assertEquals(500, world.getNationById(1)!!.gold, "national mirror is not credited")
        recorder = ChangeRecorder(); assertEquals(applied, execute(world, recorder, reserved))
        val paid = MonthlySalary(world, recorder).pay(200, 2)!!
        assertEquals(1, paid.paid); assertTrue(paid.money > 0)
        save(world, recorder); world = cold(id)
        assertEquals(1000L - paid.money, stock(world, county).stock.money)
        assertTrue(MonthlySalary(world, ChangeRecorder()).pay(200, 2)!!.alreadyStamped)
        assertEquals(applied, execute(world, ChangeRecorder(), reserved))
        assertEquals(1000L - paid.money, stock(world, county).stock.money)
    }
    @Test fun `reserved grain donation survives cold reload into real unit provisions`() {
        val id = 952; val county = seed(id); val reserved = reserve(id, "GRAIN", 2000)
        var world = cold(id); var recorder = ChangeRecorder()
        assertIs<TurnOutcome.Applied>(execute(world, recorder, reserved)); save(world, recorder)
        world = cold(id); recorder = ChangeRecorder()
        assertEquals(0, world.getGeneralById(1)!!.rice)
        assertEquals(2000L, stock(world, county).stock.grain)
        assertEquals(1, UnitResupply(world, recorder).resupply(200, 2))
        val provisions = world.getBugokById(7)!!.provisions
        assertTrue(provisions > 0)
        save(world, recorder); world = cold(id)
        assertEquals(provisions, world.getBugokById(7)!!.provisions)
        assertEquals(2000L - provisions.toLong() * opensamguk.logic.war.CampaignBalance.GRAIN_PER_PROVISION,
            stock(world, county).stock.grain)
        assertEquals(0, UnitResupply(world, ChangeRecorder()).resupply(200, 2))
    }
}
