package opensamguk.engine.boot

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.encodeCommandPayload
import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.hwiha.EnlistmentExecution
import opensamguk.engine.hwiha.EnlistmentExecutor
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.logic.input.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.TransientDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** Real inbox/flush/reload recovery; HTTP admission is covered separately by CourtApiIT. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CourtRecoveryIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: EnlistmentFixture
    private val late = Instant.parse("0200-01-01T03:00:01Z")

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker unavailable: court recovery NOT verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)))
        fixture = EnlistmentFixture(jdbc, flush)
    }
    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    private fun seed(id: Int, requestId: String) {
        fixture.seed(id)
        val world = InMemoryTurnWorld(fixture.load(id))
        val recorder = ChangeRecorder()
        assertIs<EnlistmentExecution.Applied>(EnlistmentExecutor(world, recorder)
            .execute(EnlistmentRequest(1, EnlistmentMode.NATION, 1)) { error("direct enlistment") })
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
        val county = world.administrativeCountyIds.min()
        jdbc.update("UPDATE city SET nation_id=1 WHERE world_id=? AND id=?", id, county)
        jdbc.update("UPDATE general SET user_id='40' WHERE world_id=? AND id=10", id)
        jdbc.update("UPDATE general SET user_id='42' WHERE world_id=? AND id=1", id)
        jdbc.update("UPDATE general SET turn_time='0200-01-02T00:00:00Z' WHERE world_id=? AND id<>10", id)
        val command = TurnDaemonCommand.ImmediateInput(requestId, 10, 40, "court.dispatch",
            """{"targetGeneralId":1,"countyId":$county}""")
        val payload = encodeCommandPayload(TurnDaemonCommandEnvelope(requestId, Instant.now().toString(), command))
        assertIs<CommandInboxRepository.InsertResult.Inserted>(CommandInboxRepository(NamedParameterJdbcTemplate(jdbc))
            .insertAccepted(CommandInboxRepository.AcceptedCommand(WorldId(id), requestId,
                commandKind = CommandInboxRepository.CommandKind.IMMEDIATE, intentFingerprint = "fixture-$requestId",
                generalId = 10, turnIdx = null, actionCode = "court.dispatch", payloadJson = payload, ownerUserId = 40)))
    }

    private fun count(table: String, id: Int) = jdbc.queryForObject(
        "SELECT count(*) FROM $table WHERE world_id=?", Int::class.java, id)

    @Test fun `same flush admission and issue roll back together and retained retry writes both result sequences once`() {
        val id = 101
        val request = "court-recovery-101"
        seed(id, request)
        val before = fixture.load(id)
        val published = mutableListOf<String>()
        val runner = fixture.service(WorldId(id), InMemoryTurnWorld(before), published, intake = true)
        // Fail late: general metadata, queue removal, and sequence 1 have already been attempted.
        jdbc.execute("""CREATE FUNCTION court_result_retry_probe() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN IF NEW.world_id=101 AND NEW.result_seq=2 THEN
             RAISE EXCEPTION ''court retry probe'' USING ERRCODE = ''40001'';
             END IF; RETURN NEW; END'""")
        jdbc.execute("CREATE TRIGGER court_result_retry_failure BEFORE INSERT ON command_result FOR EACH ROW EXECUTE FUNCTION court_result_retry_probe()")
        try {
            assertFailsWith<TransientDataAccessException> { runner.runDueGeneralTurns(late) }
            val rolledBack = fixture.load(id)
            assertEquals(before.generals, rolledBack.generals)
            assertEquals(before.retainers, rolledBack.retainers)
            assertEquals(before.bugoks, rolledBack.bugoks)
            assertEquals(0, count("command_result", id))
            assertEquals(0, count("command_outbox", id))
            assertTrue(published.isEmpty())
        } finally {
            jdbc.execute("DROP TRIGGER court_result_retry_failure ON command_result")
            jdbc.execute("DROP FUNCTION court_result_retry_probe()")
        }
        assertTrue(runner.retryRetainedFlush())
        assertFailsWith<IllegalStateException> { runner.retryRetainedFlush() }
        val after = fixture.load(id)
        assertNull(QueuedDispatch.read(after.generals.single { it.id == 10 }.meta))
        val dispatch = assertNotNull(DispatchState.read(after.generals.single { it.id == 1 }.meta))
        assertEquals(request, dispatch.dispatchId)
        assertEquals(DispatchStatus.PENDING, dispatch.status)
        assertEquals(listOf("reservationAccepted", "executionApplied"), jdbc.queryForList(
            "SELECT result_type FROM command_result WHERE world_id=? ORDER BY result_seq", String::class.java, id))
        assertEquals(2, count("command_outbox", id))
        assertEquals("APPLIED", jdbc.queryForObject("SELECT status FROM command_inbox WHERE world_id=?", String::class.java, id))
        assertEquals(listOf(request, request), published)
        val cold = fixture.service(WorldId(id), InMemoryTurnWorld(after), published, intake = true)
        assertTrue(cold.runDueGeneralTurns(late).handled.isEmpty())
        assertEquals(2, count("command_result", id))
        assertEquals(2, count("command_outbox", id))
        assertEquals(listOf(request, request), published)
    }

    @Test fun `queued issuer ownership change rejects once after cold reload without issuing target order`() {
        val id = 102
        val request = "court-owner-102"
        seed(id, request)
        val published = mutableListOf<String>()
        fixture.service(WorldId(id), InMemoryTurnWorld(fixture.load(id)), published, intake = true).runIntakeCommands()
        assertNotNull(QueuedDispatch.read(fixture.load(id).generals.single { it.id == 10 }.meta))
        jdbc.update("UPDATE general SET user_id='41' WHERE world_id=? AND id=10", id)
        val before = fixture.load(id)
        fixture.service(WorldId(id), InMemoryTurnWorld(before), published, intake = true).runDueGeneralTurns(late)
        val after = fixture.load(id)
        assertNull(QueuedDispatch.read(after.generals.single { it.id == 10 }.meta))
        assertEquals(before.generals.single { it.id == 1 }, after.generals.single { it.id == 1 })
        assertNull(DispatchState.read(after.generals.single { it.id == 1 }.meta))
        assertEquals(listOf("reservationAccepted", "executionRejected"), jdbc.queryForList(
            "SELECT result_type FROM command_result WHERE world_id=? ORDER BY result_seq", String::class.java, id))
        val resultPayload = opensamguk.infra.persistence.CommandResultRepository(NamedParameterJdbcTemplate(jdbc))
            .findResultPayload(WorldId(id), request)!!
        val envelope = opensamguk.common.wire.WireJson.decodeFromString(
            opensamguk.common.wire.TurnDaemonEventEnvelope.serializer(), resultPayload)
        val event = assertIs<opensamguk.common.wire.TurnDaemonEvent.CommandResult>(envelope.event)
        assertEquals("FORBIDDEN", assertIs<opensamguk.common.wire.CommandLifecycleResult>(event.result).code)
        assertEquals(40, jdbc.queryForObject("SELECT owner_user_id FROM command_inbox WHERE world_id=?", Int::class.java, id))
        assertTrue(fixture.service(WorldId(id), InMemoryTurnWorld(after), published, intake = true)
            .runDueGeneralTurns(late).handled.isEmpty())
        assertEquals(2, count("command_result", id))
        assertEquals(listOf(request, request), published)
    }

    @Test fun `tick expires new phase dispatch before processing false reply from stale starting phase`() {
        val id = 103
        val request = "court-expiry-103"
        seed(id, request)
        jdbc.update("DELETE FROM command_inbox WHERE world_id=?", id)
        val initial = InMemoryTurnWorld(fixture.load(id))
        val recorder = ChangeRecorder()
        val county = initial.administrativeCountyIds.min()
        assertIs<opensamguk.engine.hwiha.DispatchExecution.Applied>(
            opensamguk.engine.hwiha.DispatchExecutor(initial, recorder)
                .issue("original-103", DispatchRequest(10, 1, county)))
        flush.flush(DatabaseHooks.toFlushPayload(initial, recorder, initial.consumeDirtyState()))
        jdbc.update("""UPDATE world_state SET start_year=200,config=jsonb_set(config,'{startYear}','200'::jsonb),start_time='0200-01-01T00:00:00Z',
            current_month=4,current_phase=3,
            meta=jsonb_set(meta,'{lastTurnTime}','"0200-01-01T11:00:00Z"'::jsonb) WHERE id=?""", id)
        jdbc.update("UPDATE general SET turn_time='0200-01-02T00:00:00Z' WHERE world_id=?", id)
        val command = TurnDaemonCommand.ImmediateInput(request, 1, 42, "court.dispatchReply",
            """{"dispatchId":"original-103","accept":false}""")
        val payload = encodeCommandPayload(TurnDaemonCommandEnvelope(request, Instant.now().toString(), command))
        CommandInboxRepository(NamedParameterJdbcTemplate(jdbc)).insertAccepted(
            CommandInboxRepository.AcceptedCommand(WorldId(id), request,
                commandKind = CommandInboxRepository.CommandKind.IMMEDIATE, intentFingerprint = "expiry-$request",
                generalId = 1, turnIdx = null, actionCode = "court.dispatchReply", payloadJson = payload, ownerUserId = 42))
        val before = fixture.load(id)
        assertEquals(200, before.state.meta["startYear"])
        assertEquals(Phase(200, 5, 1), DispatchState.read(before.generals.single { it.id == 1 }.meta)!!.dueAt)
        val published = mutableListOf<String>()
        fixture.service(WorldId(id), InMemoryTurnWorld(before), published, intake = true)
            .runTick(Instant.parse("0200-01-01T12:00:00Z"))
        val after = fixture.load(id)
        val target = after.generals.single { it.id == 1 }
        assertEquals(DispatchStatus.ACCEPTED, DispatchState.read(target.meta)!!.status)
        assertNotNull(CountyAssignment.read(target.meta))
        assertEquals(before.retainers, after.retainers)
        assertEquals(PersonPolicyState.read(before.generals.single { it.id == 1 }.meta),
            PersonPolicyState.read(target.meta))
        assertEquals(before.generals.single { it.id == 1 }.turnTime, target.turnTime)
        val resultPayload = opensamguk.infra.persistence.CommandResultRepository(NamedParameterJdbcTemplate(jdbc))
            .findResultPayload(WorldId(id), request)!!
        val event = opensamguk.common.wire.WireJson.decodeFromString(
            opensamguk.common.wire.TurnDaemonEventEnvelope.serializer(), resultPayload).event
        assertEquals("ALREADY_RESOLVED", assertIs<opensamguk.common.wire.CommandLifecycleResult>(
            assertIs<opensamguk.common.wire.TurnDaemonEvent.CommandResult>(event).result).code)
        assertEquals(listOf(request), published)
    }

    @Test fun `tick queues after issuer personal action and executes only in next eligible phase`() {
        val id = 104
        val request = "court-next-phase-104"
        seed(id, request)
        jdbc.update("UPDATE world_state SET start_year=200,config=jsonb_set(config,'{startYear}','200'::jsonb),start_time='0200-01-01T00:00:00Z' WHERE id=?", id)
        val published = mutableListOf<String>()
        val withinFirstPhase = Instant.parse("0200-01-01T00:30:00Z")
        val seeded = fixture.load(id)
        assertEquals(200, seeded.state.meta["startYear"])
        val first = fixture.service(WorldId(id), InMemoryTurnWorld(seeded), published, intake = true)
            .runTick(withinFirstPhase)
        assertEquals(listOf(10), first.handled.map { it.generalId })
        val queued = fixture.load(id)
        assertNotNull(QueuedDispatch.read(queued.generals.single { it.id == 10 }.meta))
        assertNull(DispatchState.read(queued.generals.single { it.id == 1 }.meta))
        assertEquals(1, count("command_result", id))
        // The due wall-clock alone cannot reuse the already consumed current phase.
        assertTrue(fixture.service(WorldId(id), InMemoryTurnWorld(queued), published, intake = true)
            .runDueGeneralTurns(late).handled.isEmpty())
        assertNotNull(QueuedDispatch.read(fixture.load(id).generals.single { it.id == 10 }.meta))
        val next = InMemoryTurnWorld(fixture.load(id))
        next.setCurrentDate(200, 1, 2)
        fixture.service(WorldId(id), next, published, intake = true).runDueGeneralTurns(late)
        val after = fixture.load(id)
        assertNull(QueuedDispatch.read(after.generals.single { it.id == 10 }.meta))
        assertEquals(request, DispatchState.read(after.generals.single { it.id == 1 }.meta)!!.dispatchId)
        assertEquals(listOf("reservationAccepted", "executionApplied"), jdbc.queryForList(
            "SELECT result_type FROM command_result WHERE world_id=? ORDER BY result_seq", String::class.java, id))
        assertEquals(listOf(request, request), published)
    }

}
