package opensamguk.engine.boot

import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.hwiha.*
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.logic.input.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assumptions
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** Real HWIHA load/flush boundary; the small character fixture is not a playable Zhou scenario. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HwihaEnlistmentPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: HwihaEnlistmentFixture

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: HWIHA enlistment database roundtrip NOT verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)))
        fixture = HwihaEnlistmentFixture(jdbc, flush)
    }
    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    private fun seed(id: Int) = fixture.seed(id)
    private fun load(id: Int) = fixture.load(id)
    private fun enlist(world: InMemoryTurnWorld, recorder: ChangeRecorder) = HwihaEnlistmentExecutor(world, recorder).execute(EnlistmentRequest(1, EnlistmentMode.NATION, 1)) { error("no random draw") }

    private fun assertSameSnapshot(expected: WorldSnapshot, actual: WorldSnapshot) {
        // Spatial snapshots are immutable classes without value equality; compare their contents.
        assertEquals(expected.waterControlSnapshot!!.statesByZoneId, actual.waterControlSnapshot!!.statesByZoneId)
        assertEquals(expected.provinceControlSnapshot!!.statesByProvinceId, actual.provinceControlSnapshot!!.statesByProvinceId)
        assertEquals(expected.generalPositionSnapshot!!.statesByGeneralId, actual.generalPositionSnapshot!!.statesByGeneralId)
        assertEquals(expected.waterControlSnapshot!!.topologyHash, actual.waterControlSnapshot!!.topologyHash)
        assertEquals(expected.provinceControlSnapshot!!.topologyHash, actual.provinceControlSnapshot!!.topologyHash)
        assertEquals(expected.generalPositionSnapshot!!.topologyHash, actual.generalPositionSnapshot!!.topologyHash)
        assertEquals(expected.waterControlSnapshot!!.topologyRevision, actual.waterControlSnapshot!!.topologyRevision)
        assertEquals(expected.provinceControlSnapshot!!.topologyRevision, actual.provinceControlSnapshot!!.topologyRevision)
        assertEquals(expected.generalPositionSnapshot!!.topologyRevision, actual.generalPositionSnapshot!!.topologyRevision)
        assertEquals(expected, actual.copy(waterControlSnapshot = expected.waterControlSnapshot,
            provinceControlSnapshot = expected.provinceControlSnapshot, generalPositionSnapshot = expected.generalPositionSnapshot))
    }

    @Test fun `real HWIHA snapshot enlistment flush and cold reload preserve personal assets and prevent duplicate relation`() {
        seed(1); seed(2)
        val before = load(1)
        val otherWorld = load(2)
        assertEquals(RuleProfile.HWIHA, before.state.ruleProfile)
        val world = InMemoryTurnWorld(before)
        val recorder = ChangeRecorder()
        val result = assertIs<EnlistmentExecution.Applied>(enlist(world, recorder))
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
        val after = load(1)
        assertEquals(world.listGenerals(), after.generals)
        assertEquals(world.listNations(), after.nations)
        assertEquals(world.listRetainers(), after.retainers)
        assertEquals(before.bugoks, after.bugoks)
        assertEquals(before.cities, after.cities)
        assertEquals(before.generalPositionSnapshot!!.statesByGeneralId, after.generalPositionSnapshot!!.statesByGeneralId)
        assertSameSnapshot(otherWorld, load(2))
        assertEquals(3, after.nations.single().meta["gennum"])
        assertEquals(false, HwihaLordStatus.read(after.generals.single { it.id == 1 }.meta))
        val rebooted = InMemoryTurnWorld(after)
        assertEquals(HwihaPersonPolicyState(30, true, "synthetic-storage-fixture", "fixture-v1", 1),
            HwihaPersonPolicyState.read(rebooted.getGeneralById(1)!!.meta))
        val coldPolicy = assertIs<HwihaEnlistmentPolicyResult.Ready>(HwihaEnlistmentPolicy(rebooted)
            .current(EnlistmentRequest(1, EnlistmentMode.NATION, 1)))
        assertEquals(7, coldPolicy.policy.actorCardCost)
        assertEquals(23, coldPolicy.policy.freeRenownByLord[10])
        assertEquals(30, HwihaPersonPolicyState.read(rebooted.getGeneralById(10)!!.meta)!!.renownCapacity)
        assertEquals(EnlistmentFailure.ALREADY_SERVING,
            assertIs<EnlistmentExecution.Rejected>(enlist(rebooted, ChangeRecorder())).reason)
        assertEquals(2, rebooted.listRetainers().size)
        assertEquals(result.retainerId + 1, rebooted.allocateRetainerId())
    }

    @Test fun `card insert failure rolls back earlier nation and general updates and the payload can be retried`() {
        seed(3)
        val before = load(3)
        val world = InMemoryTurnWorld(before)
        val recorder = ChangeRecorder()
        assertIs<EnlistmentExecution.Applied>(enlist(world, recorder))
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        jdbc.execute("ALTER TABLE general_retainers ADD CONSTRAINT enlistment_failure_probe CHECK (NOT (world_id=3 AND general_id=1))")
        try {
            assertFailsWith<DataIntegrityViolationException> { flush.flush(payload) }
            assertSameSnapshot(before, load(3))
        } finally {
            jdbc.execute("ALTER TABLE general_retainers DROP CONSTRAINT enlistment_failure_probe")
        }
        flush.flush(payload)
        assertEquals(world.listGenerals(), load(3).generals)
        assertEquals(world.listRetainers(), load(3).retainers)
        assertEquals(world.listNations(), load(3).nations)
    }
    private fun service(id: WorldId, active: InMemoryTurnWorld, published: MutableList<String>) = fixture.service(id, active, published)

    @Test fun `undelivered input result slot consumption and personal time commit together and survive restart`() {
        seed(4)
        jdbc.update("UPDATE general SET turn_time='0200-01-02T00:00:00Z' WHERE world_id=4 AND id<>1")
        val id = WorldId(4)
        val named = NamedParameterJdbcTemplate(jdbc)
        val reservations = opensamguk.infra.persistence.ReservedTurnRepository(named)
        reservations.reserve(id, 1, 0, "placement.assign", "{}", requestId = "undelivered-request")
        reservations.reserve(id, 1, 1, "court.dispatch", "{}", requestId = "next-request")
        val before = load(4)
        val world = InMemoryTurnWorld(before)
        val published = mutableListOf<String>()
        val at = java.time.Instant.parse("0200-01-01T00:00:01Z")
        val result = service(id, world, published).runDueGeneralTurns(at).handled.single()
        assertEquals("NOT_DELIVERED", assertIs<HwihaTurnOutcome.Rejected>(result.hwihaOutcome).code)
        assertEquals(listOf("undelivered-request"), published)
        assertEquals("next-request", reservations.readReserved(id, 1, 0).requestId)
        val after = load(4)
        assertEquals(before.generals.map { if (it.id == 1) it.copy(
            turnTime = it.turnTime.plusSeconds(3600),
            meta = HwihaPersonalTurn.after(it.meta, world.getState()),
            initialTurns = it.initialTurns.drop(1) + GeneralTurnSeed("휴식", "{}", "휴식"),
        ) else it }, after.generals)
        assertEquals(before.retainers, after.retainers)
        val payload = opensamguk.infra.persistence.CommandResultRepository(named).findResultPayload(id, "undelivered-request")!!
        val envelope = opensamguk.common.wire.WireJson.decodeFromString(
            opensamguk.common.wire.TurnDaemonEventEnvelope.serializer(), payload)
        val stored = (envelope.event as opensamguk.common.wire.TurnDaemonEvent.CommandResult).result
            as opensamguk.common.wire.CommandLifecycleResult
        assertEquals("executionRejected", stored.type)
        assertFalse(stored.ok)
        assertEquals("NOT_DELIVERED", stored.code)
        assertEquals("placement.assign", stored.actionCode)
        assertTrue(service(id, InMemoryTurnWorld(after), published).runDueGeneralTurns(at).handled.isEmpty())
        assertEquals("next-request", reservations.readReserved(id, 1, 0).requestId)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM command_result WHERE world_id=4 AND request_id='undelivered-request'", Int::class.java))
        assertEquals(1, published.size)
    }

    @Test fun `reserved enlistment success persists once despite overdue repeated drains and cold restart`() {
        seed(5)
        jdbc.update("UPDATE general SET turn_time='0200-01-02T00:00:00Z' WHERE world_id=5 AND id<>1")
        val id = WorldId(5)
        val named = NamedParameterJdbcTemplate(jdbc)
        val reservations = opensamguk.infra.persistence.ReservedTurnRepository(named)
        val args = """{"mode":"NATION","targetId":1}"""
        reservations.reserve(id, 1, 0, "action.enlist", args, requestId = "enlist-success")
        reservations.reserve(id, 1, 1, "action.enlist", args, requestId = "enlist-again")
        val before = load(5)
        val world = InMemoryTurnWorld(before)
        val published = mutableListOf<String>()
        val runner = service(id, world, published)
        val late = java.time.Instant.parse("0200-01-01T03:00:01Z")
        val handled = runner.runDueGeneralTurns(late).handled.single()
        assertIs<HwihaTurnOutcome.Applied>(handled.hwihaOutcome)
        assertNull(handled.definition)
        assertFalse(handled.fellBack)
        assertEquals("enlist-success", handled.requestId)
        assertTrue(runner.runDueGeneralTurns(late).handled.isEmpty())
        assertEquals(listOf("enlist-success"), published)
        val after = load(5)
        assertEquals(setOf(1), after.generals.map { it.nationId }.toSet())
        assertFalse(HwihaLordStatus.read(after.generals.single { it.id == 1 }.meta))
        assertEquals(2, after.retainers.size)
        assertEquals(10, after.retainers.single { it.generalId == 1 }.masterGeneralId)
        assertEquals(before.bugoks, after.bugoks)
        assertEquals(before.cities, after.cities)
        assertEquals(before.generalPositionSnapshot!!.statesByGeneralId, after.generalPositionSnapshot!!.statesByGeneralId)
        val cold = InMemoryTurnWorld(after)
        assertTrue(service(id, cold, published).runDueGeneralTurns(late).handled.isEmpty())
        assertEquals("enlist-again", reservations.readReserved(id, 1, 0).requestId)
        val results = opensamguk.infra.persistence.CommandResultRepository(named)
        fun result(request: String): opensamguk.common.wire.CommandLifecycleResult {
            val envelope = opensamguk.common.wire.WireJson.decodeFromString(
                opensamguk.common.wire.TurnDaemonEventEnvelope.serializer(), results.findResultPayload(id, request)!!)
            return (envelope.event as opensamguk.common.wire.TurnDaemonEvent.CommandResult).result
                as opensamguk.common.wire.CommandLifecycleResult
        }
        assertTrue(result("enlist-success").ok)
        assertEquals("executionApplied", result("enlist-success").type)
        assertEquals("action.enlist", result("enlist-success").actionCode)
        cold.setCurrentDate(200, 1, 2)
        val second = service(id, cold, published).runDueGeneralTurns(late).handled.single()
        assertEquals("ALREADY_SERVING", assertIs<HwihaTurnOutcome.Rejected>(second.hwihaOutcome).code)
        assertFalse(result("enlist-again").ok)
        assertEquals(2, load(5).retainers.size)
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM command_result WHERE world_id=5", Int::class.java))
        assertEquals(listOf("enlist-success", "enlist-again"), published)
    }

    @Test fun `reserved success flush failure rolls back action phase stamp slot and result then retries once`() {
        seed(6)
        jdbc.update("UPDATE general SET turn_time='0200-01-02T00:00:00Z' WHERE world_id=6 AND id<>1")
        val id = WorldId(6)
        val named = NamedParameterJdbcTemplate(jdbc)
        val reservations = opensamguk.infra.persistence.ReservedTurnRepository(named)
        reservations.reserve(id, 1, 0, "action.enlist", """{"mode":"GENERAL","targetId":10}""", requestId = "enlist-retry")
        val before = load(6)
        val world = InMemoryTurnWorld(before)
        val published = mutableListOf<String>()
        val runner = service(id, world, published)
        val late = java.time.Instant.parse("0200-01-01T03:00:01Z")
        jdbc.execute("""CREATE FUNCTION enlistment_transient_probe() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN IF NEW.world_id=6 AND NEW.general_id=1 THEN
             RAISE EXCEPTION ''serialization retry probe'' USING ERRCODE = ''40001'';
             END IF; RETURN NEW; END'""")
        jdbc.execute("CREATE TRIGGER enlistment_phase_failure BEFORE INSERT ON general_retainers FOR EACH ROW EXECUTE FUNCTION enlistment_transient_probe()")
        try {
            assertFailsWith<org.springframework.dao.TransientDataAccessException> { runner.runDueGeneralTurns(late) }
            assertSameSnapshot(before, load(6))
            assertEquals("enlist-retry", reservations.readReserved(id, 1, 0).requestId)
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM command_result WHERE world_id=6", Int::class.java))
            assertTrue(published.isEmpty())
        } finally {
            jdbc.execute("DROP TRIGGER enlistment_phase_failure ON general_retainers")
            jdbc.execute("DROP FUNCTION enlistment_transient_probe()")
        }
        assertTrue(runner.retryRetainedFlush())
        assertFailsWith<IllegalStateException> { runner.retryRetainedFlush() }
        val cold = InMemoryTurnWorld(load(6))
        assertEquals(2, cold.listRetainers().size)
        assertTrue(service(id, cold, published).runDueGeneralTurns(late).handled.isEmpty())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM command_result WHERE world_id=6", Int::class.java))
        assertEquals(listOf("enlist-retry"), published)
    }

}
