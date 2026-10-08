package opensamguk.engine.politics

import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.boot.EnlistmentFixture
import opensamguk.engine.campaign.*
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.*
import opensamguk.logic.input.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.TransientDataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** action.rise: Actual PG transaction, reserved daemon execution, durable result and cold snapshot restart. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RisePersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var fixture: EnlistmentFixture
    private lateinit var reservations: ReservedTurnRepository
    private val due = Instant.parse("0200-01-01T00:00:01Z")

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: rise lifecycle PostgreSQL/restart NOT verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        val named = NamedParameterJdbcTemplate(source)
        fixture = EnlistmentFixture(jdbc, JdbcFlushExecutor(named,
            TransactionTemplate(DataSourceTransactionManager(source))))
        reservations = ReservedTurnRepository(named)
    }

    @AfterAll fun cleanup() { if (this::postgres.isInitialized) postgres.stop() }

    private fun seed(id: Int) {
        fixture.seed(id)
        val assignment = CountyAssignment("previous-office", 10, 1, fixture.load(id).generals.first { it.id == 1 }.cityId)
        // HWIHA's canonical ledger for the fixture's existing ten county troops; do not normalize assets after execution.
        jdbc.update("UPDATE city SET meta=jsonb_set(meta,'{cityMilitary}',?::jsonb) WHERE world_id=? AND id=?",
            MetaJson.encode(CityMilitaryState(50, 50, 10).toMetaValue()), id, assignment.countyId)
        jdbc.update("""UPDATE general SET user_id=42,troop_id=1,officer_level=3,meta=?::jsonb
            WHERE world_id=? AND id=1""", MetaJson.encode(mapOf(LordStatus.META_KEY to false,
            "keep" to "unchanged", "makelimit" to 37, "officer_city" to assignment.countyId,
            CountyAssignment.META_KEY to assignment.toMetaValue(),
            QueuedCourtAction.META_KEY to QueuedCourtAction("old-order", 42, "court.releaseCorps", "{}").toMetaValue())), id)
        jdbc.update("UPDATE general SET troop_id=1 WHERE world_id=? AND id=2", id)
        jdbc.update("UPDATE general SET turn_time='0200-01-02T00:00:00Z' WHERE world_id=? AND id<>1", id)
        jdbc.update("INSERT INTO troop(world_id,troop_leader,nation,name) VALUES (?,1,0,'owned military asset')", id)
        jdbc.update("UPDATE general_bugok SET fatigue=17 WHERE world_id=?", id)
    }

    private fun reserve(id: Int, request: String, slot: Int = 0) {
        val inbox = CommandInboxRepository(NamedParameterJdbcTemplate(jdbc))
        assertEquals(CommandInboxRepository.InsertResult.Inserted, inbox.insertAccepted(
            CommandInboxRepository.AcceptedCommand(WorldId(id), request,
                commandKind = CommandInboxRepository.CommandKind.RESERVED_TURN, intentFingerprint = request,
                generalId = 1, turnIdx = slot, actionCode = PoliticalInput.RISE, payloadJson = "{}", ownerUserId = 42)))
        reservations.reserve(WorldId(id), 1, slot, PoliticalInput.RISE, "{}", requestId = request)
        assertEquals(42, reservations.readReserved(WorldId(id), 1, slot).reservationOwnerUserId)
    }

    private fun assertAssets(before: WorldSnapshot, after: WorldSnapshot, nationId: Int) {
        assertEquals(before.retainers, after.retainers)
        assertEquals(before.bugoks, after.bugoks)
        assertEquals(before.troops.map { it.copy(nationId = nationId) }, after.troops)
        for (person in before.generals) {
            val next = after.generals.single { it.id == person.id }
            assertEquals(person.copy(nationId = next.nationId, officerLevel = next.officerLevel,
                turnTime = if (person.id == 1) person.turnTime.plusSeconds(3600) else person.turnTime,
                initialTurns = if (person.id == 1) person.initialTurns.drop(1) else person.initialTurns,
                meta = next.meta), next)
            if (person.id in 1..2) {
                assertEquals(nationId, next.nationId)
                assertEquals(if (person.id == 1) 12 else 0, next.officerLevel)
                assertEquals(person.id == 1, LordStatus.read(next.meta))
                assertFalse(CountyAssignment.META_KEY in next.meta)
                assertFalse(QueuedCourtAction.META_KEY in next.meta)
            } else assertEquals(person, next)
        }
        assertEquals(37, after.generals.single { it.id == 1 }.meta["makelimit"])
        assertEquals(before.generalPositionSnapshot!!.statesByGeneralId, after.generalPositionSnapshot!!.statesByGeneralId)
        assertEquals(before.nations, after.nations.filter { it.id != nationId })
        val seat = before.generals.single { it.id == 1 }.cityId
        assertEquals(before.cities.map { if (it.id == seat) it.copy(nationId = nationId) else it }, after.cities)
        val nation = after.nations.single { it.id == nationId }
        assertEquals(1, nation.chiefGeneralId)
        assertEquals(seat, nation.capitalCityId)
        assertEquals(2, (nation.meta["gennum"] as Number).toInt())
        assertEquals(setOf(nationId to 1, 1 to nationId), after.diplomacy.map { it.fromNationId to it.toNationId }.toSet())
        assertTrue(after.diplomacy.all { it.state == 2 && it.term == 0 })
    }

    private fun assertReceipt(id: Int, request: String, expectedType: String = "executionApplied") {
        assertEquals(listOf(expectedType), jdbc.queryForList(
            "SELECT result_type FROM command_result WHERE world_id=? AND request_id=?", String::class.java, id, request))
    }

    @Test fun `free troop storage needs no fictitious neutral nation and still rejects foreign references`() {
        val id = 7463
        seed(id)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM nation WHERE world_id=? AND id=0", Int::class.java, id))
        assertEquals(0, fixture.load(id).troops.single().nationId)
        fixture.seed(7464)
        jdbc.update("INSERT INTO nation(world_id,id,name,color) VALUES (7464,9,'other world','#999999')")
        for (nation in listOf(9, 999, -1)) assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("INSERT INTO troop(world_id,troop_leader,nation,name) VALUES (?,2,?,'invalid reference')", id, nation)
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("INSERT INTO troop(world_id,troop_leader,nation,name) VALUES (?,999,0,'unknown leader')", id)
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("INSERT INTO troop(world_id,troop_leader,nation,name) VALUES (999999,1,0,'unknown world')")
        }
        assertEquals(1, fixture.load(id).troops.size)
        assertEquals(1, fixture.load(id).nations.size)
    }

    @Test fun `reserved rise commits assets results and slot once and cannot execute again after restart`() {
        val id = 7461
        seed(id)
        reserve(id, "rise-once")
        reserve(id, "rise-next", 1)
        val before = fixture.load(id)
        val world = InMemoryTurnWorld(before)
        val published = mutableListOf<String>()
        val runner = fixture.service(WorldId(id), world, published)
        assertIs<TurnOutcome.Applied>(runner.runDueGeneralTurns(due).handled.single().inputOutcome)
        val after = fixture.load(id)
        assertAssets(before, after, 2)
        assertReceipt(id, "rise-once")
        assertEquals(listOf("rise-once"), published)
        assertEquals("rise-next", reservations.readReserved(WorldId(id), 1, 0).requestId)
        assertTrue(runner.runDueGeneralTurns(due).handled.isEmpty())
        val cold = InMemoryTurnWorld(fixture.load(id))
        assertTrue(fixture.service(WorldId(id), cold, published).runDueGeneralTurns(due).handled.isEmpty())
        assertAssets(before, fixture.load(id), 2)
        assertEquals("rise-next", reservations.readReserved(WorldId(id), 1, 0).requestId)
        cold.setCurrentDate(200, 1, 2)
        assertEquals(PoliticalFailure.NOT_FREE.name, assertIs<TurnOutcome.Rejected>(
            fixture.service(WorldId(id), cold, published).runDueGeneralTurns(due.plusSeconds(3600))
                .handled.single().inputOutcome).code)
        assertReceipt(id, "rise-next", "executionRejected")
        assertEquals(2, fixture.load(id).nations.size)
        assertEquals(1, fixture.load(id).troops.size)
    }

    @Test fun `late transient flush rolls back nation assets slot and result then retries the retained batch once`() {
        val id = 7462
        seed(id)
        reserve(id, "rise-retry")
        val before = fixture.load(id)
        val published = mutableListOf<String>()
        val runner = fixture.service(WorldId(id), InMemoryTurnWorld(before), published)
        jdbc.execute("""CREATE FUNCTION rise_transient_probe() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN IF NEW.world_id=7462 THEN
            RAISE EXCEPTION ''rise transaction retry probe'' USING ERRCODE = ''40001'';
            END IF; RETURN NEW; END'""")
        jdbc.execute("CREATE TRIGGER rise_result_failure BEFORE INSERT ON command_result FOR EACH ROW EXECUTE FUNCTION rise_transient_probe()")
        try {
            assertFailsWith<TransientDataAccessException> { runner.runDueGeneralTurns(due) }
            val rolledBack = fixture.load(id)
            assertEquals(before.generals, rolledBack.generals)
            assertEquals(before.nations, rolledBack.nations)
            assertEquals(before.cities, rolledBack.cities)
            assertEquals(before.troops, rolledBack.troops)
            assertEquals(before.retainers, rolledBack.retainers)
            assertEquals(before.bugoks, rolledBack.bugoks)
            assertEquals(before.diplomacy, rolledBack.diplomacy)
            assertEquals(before.state.meta, rolledBack.state.meta)
            assertEquals("rise-retry", reservations.readReserved(WorldId(id), 1, 0).requestId)
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM command_result WHERE world_id=?", Int::class.java, id))
            assertTrue(published.isEmpty())
            assertFailsWith<IllegalStateException> { runner.runDueGeneralTurns(due) }
        } finally {
            jdbc.execute("DROP TRIGGER rise_result_failure ON command_result")
            jdbc.execute("DROP FUNCTION rise_transient_probe()")
        }
        assertTrue(runner.retryRetainedFlush())
        assertFailsWith<IllegalStateException> { runner.retryRetainedFlush() }
        assertAssets(before, fixture.load(id), 2)
        assertReceipt(id, "rise-retry")
        val cold = InMemoryTurnWorld(fixture.load(id))
        assertTrue(fixture.service(WorldId(id), cold, published).runDueGeneralTurns(due).handled.isEmpty())
        assertEquals(listOf("rise-retry"), published)
        assertAssets(before, fixture.load(id), 2)
    }
}
