package opensamguk.engine.boot

import kotlin.test.*
import opensamguk.engine.campaign.DomesticContext
import opensamguk.engine.campaign.RetireHandler
import opensamguk.engine.campaign.TurnOutcome
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.council.CurrentRulerBinding
import opensamguk.logic.input.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/** Cold reload of authoritative eligibility fields, with retirement still closed in production. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class RetireEligibilityPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: EnlistmentFixture

    @BeforeAll fun setup() {
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source),
            TransactionTemplate(DataSourceTransactionManager(source)))
        fixture = EnlistmentFixture(jdbc, flush)
    }

    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    private fun deliveredCatalog(): InputCatalog {
        val original = checkNotNull(javaClass.classLoader.getResource("command-catalog/input-catalog.json")).readText()
        val row = Regex("""("inputId":\s*"action\.retire"[\s\S]*?"deliveryState":\s*")PLANNED(")""")
        assertTrue(row.containsMatchIn(original))
        return InputCatalog.parse(row.replace(original, "${'$'}1HANDLER_READY${'$'}2"))
    }

    private fun seedRenownWorld(id: Int, capacity: Int) {
        fixture.seed(id)
        jdbc.update("""INSERT INTO general_owner(world_id,general_id,user_id,claim_request_id)
            VALUES (?,1,42,'synthetic-retiring-owner'),(?,2,44,'synthetic-existing-heir-owner'),
                   (?,10,46,'synthetic-unrelated-owner')""", id, id, id)
        jdbc.update("UPDATE general SET nation_id=1 WHERE world_id=? AND id IN (1,2)", id)
        jdbc.update("UPDATE general SET user_id=42,age=60,officer_level=12 WHERE world_id=? AND id=1", id)
        jdbc.update("UPDATE general SET meta=jsonb_set(meta,'{lord}','false') WHERE world_id=? AND id=10", id)
        setCapacity(id, capacity)
        val meta = CurrentRulerBinding.with(mapOf("gennum" to 3, "keep" to 42), 1, "synthetic-seed-$id",
            CurrentRulerBinding.SCENARIO_SEED_SOURCE)
        jdbc.update("UPDATE nation SET meta=?::jsonb WHERE world_id=? AND id=1", MetaJson.encode(meta), id)
        jdbc.update("""INSERT INTO general_retainers
            (world_id,id,master_general_id,origin,general_id,name,relation,has_own_bugok,release_policy)
            VALUES (?,5,1,'EXISTING',10,'G10','staff',false,'MUTUAL')""", id)
        for (generalId in listOf(1, 2)) jdbc.update("""INSERT INTO general_turn
            (world_id,general_id,turn_idx,action_code,arg,request_id)
            VALUES (?,?,1,?,'{"stat":"strength"}'::jsonb,?)""",
            id, generalId, PersonalInput.SELF_TRAIN, "synthetic-renown-$generalId")
    }

    private fun setCapacity(id: Int, capacity: Int) {
        jdbc.update("UPDATE general SET meta=jsonb_set(meta,'{personPolicy,renownCapacity}',?::jsonb) WHERE world_id=? AND id=2",
            capacity.toString(), id)
    }

    private fun storedSuccessionState(id: Int) = listOf(
        "general", "general_retainers", "general_bugok", "general_turn", "general_owner", "nation", "general_spatial_position",
    ).associateWith { table -> jdbc.queryForList("SELECT * FROM $table WHERE world_id=? ORDER BY 1,2", id) }

    private fun seedFullQueues(id: Int) {
        jdbc.update("DELETE FROM general_turn WHERE world_id=?", id)
        for (generalId in listOf(1, 2, 10)) for (slot in 0 until 12) jdbc.update("""INSERT INTO general_turn
            (world_id,general_id,turn_idx,action_code,arg,request_id)
            VALUES (?,?,?,?, '{}'::jsonb,?)""", id, generalId, slot, PersonalInput.SELF_TRAIN,
            "synthetic-retire-$id-$generalId-$slot")
    }

    @Test fun `committed retirement replays after turn advance and cold reload without another flush write`() {
        val id = 780
        seedRenownWorld(id, 7)
        seedFullQueues(id)
        val otherWorldId = 781
        seedRenownWorld(otherWorldId, 7)
        seedFullQueues(otherWorldId)
        val otherWorldBefore = storedSuccessionState(otherWorldId)
        val queuesBefore = jdbc.queryForList("SELECT * FROM general_turn WHERE world_id=? ORDER BY general_id,turn_idx", id)
        val ownersBefore = jdbc.queryForList("SELECT * FROM general_owner WHERE world_id=? ORDER BY general_id", id)
        val world = InMemoryTurnWorld(fixture.load(id))
        val recorder = ChangeRecorder()
        val result = assertIs<TurnOutcome.Applied>(RetireHandler(world, recorder, DomesticContext(), deliveredCatalog())
            .handle(1, """{"successorGeneralId":2}""", "retire-reload-780", 42))
        assertEquals(listOf("successorGeneralId:2", "retainers:1", "bugoks:1"), result.effects)
        // The ordinary lifecycle advances the retired actor before the same transaction is flushed.
        val retired = world.getGeneralById(1)!!
        val advanced = retired.copy(turnTime = retired.turnTime.plusSeconds(3600))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(retired), PerTurnOverlay.toLogicGeneral(advanced))
        world.applyGeneralDirtyFree(advanced)
        // Match the daemon's normal due-slot consumption, before clearing the remaining queue.
        recorder.recordGeneralTurnPull(1, expectedReservation = opensamguk.infra.persistence.ReservedTurnRepository(
            NamedParameterJdbcTemplate(jdbc.dataSource!!)).readReserved(opensamguk.common.world.WorldId(id), 1, 0))
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
        assertTrue(flush.lastOps().isNotEmpty())
        assertEquals(ownersBefore.filter { (it["general_id"] as Number).toInt() != 1 },
            jdbc.queryForList("SELECT * FROM general_owner WHERE world_id=? ORDER BY general_id", id))
        assertEquals(queuesBefore.filter { (it["general_id"] as Number).toInt() != 1 },
            jdbc.queryForList("SELECT * FROM general_turn WHERE world_id=? ORDER BY general_id,turn_idx", id))
        assertEquals(otherWorldBefore, storedSuccessionState(otherWorldId))
        val stored = storedSuccessionState(id)
        repeat(2) {
            val loaded = InMemoryTurnWorld(fixture.load(id))
            assertEquals(advanced.turnTime, loaded.getGeneralById(1)!!.turnTime)
            assertNull(loaded.getGeneralById(1)!!.userId)
            assertEquals("42", loaded.getGeneralById(2)!!.userId)
            assertEquals(2000, loaded.getGeneralById(2)!!.gold)
            assertEquals(4000, loaded.getGeneralById(2)!!.rice)
            assertEquals(listOf(5), loaded.listRetainers().map { it.id })
            assertEquals(2, loaded.listBugoks().single().masterGeneralId)
            assertNull(loaded.listBugoks().single().commanderRetainerId)
            assertEquals(2, loaded.getNationById(1)!!.chiefGeneralId)
            // This shared fixture's legacy city projection initializes military ledgers on every load.
            // Flush that bootstrap separately so the replay's delta is measured independently.
            flush.flush(DatabaseHooks.toFlushPayload(loaded, ChangeRecorder(), loaded.consumeDirtyState()))
            assertEquals(stored, storedSuccessionState(id))
            val empty = loaded.consumeDirtyState()
            val snapshot = listOf(loaded.listGenerals(), loaded.listRetainers(), loaded.listBugoks(), loaded.listNations(),
                loaded.generalPositionSnapshot())
            val replayRecorder = ChangeRecorder()
            val handler = RetireHandler(loaded, replayRecorder, DomesticContext())
            assertEquals(result, handler.handle(1, """{"successorGeneralId":2}""", "retire-reload-780", 42))
            for ((requestId, owner, successor) in listOf(Triple("other-request", 42, 2), Triple(null, 42, 2),
                Triple("retire-reload-780", 43, 2), Triple("retire-reload-780", 42, 10))) {
                assertEquals(RetireFailure.ALREADY_RETIRED.name, assertIs<TurnOutcome.Rejected>(
                    handler.handle(1, """{"successorGeneralId":$successor}""", requestId, owner)).code)
            }
            assertEquals(snapshot, listOf(loaded.listGenerals(), loaded.listRetainers(), loaded.listBugoks(),
                loaded.listNations(), loaded.generalPositionSnapshot()))
            assertFalse(replayRecorder.isDirty)
            val replayDirty = loaded.consumeDirtyState()
            assertEquals(empty, replayDirty)
            flush.flush(DatabaseHooks.toFlushPayload(loaded, replayRecorder, replayDirty))
            // The shared flush always updates world_state, including for an empty payload.
            assertTrue(flush.lastOps().all { it.table == "world_state" }, "replay must not stage any additional flush operation")
            assertEquals(stored, storedSuccessionState(id))
        }
        jdbc.update("UPDATE general SET meta=jsonb_set(meta,'{retireLastTurn,effects}','[\"broken\"]') WHERE world_id=? AND id=1", id)
        val damaged = storedSuccessionState(id)
        val loaded = InMemoryTurnWorld(fixture.load(id))
        flush.flush(DatabaseHooks.toFlushPayload(loaded, ChangeRecorder(), loaded.consumeDirtyState()))
        assertEquals(damaged, storedSuccessionState(id))
        val replayRecorder = ChangeRecorder()
        assertEquals(RetireFailure.STATE_UNAVAILABLE.name, assertIs<TurnOutcome.Rejected>(
            RetireHandler(loaded, replayRecorder, DomesticContext()).handle(1,
                """{"successorGeneralId":2}""", "retire-reload-780", 42)).code)
        assertFalse(replayRecorder.isDirty)
        flush.flush(DatabaseHooks.toFlushPayload(loaded, replayRecorder, loaded.consumeDirtyState()))
        assertTrue(flush.lastOps().all { it.table == "world_state" })
        assertEquals(damaged, storedSuccessionState(id))
    }

    @Test fun `successful unowned NPC retirement preserves ownership rows and queued reservations`() {
        val id = 783
        seedRenownWorld(id, 7)
        seedFullQueues(id)
        jdbc.update("UPDATE general SET user_id=NULL,npc_state=2 WHERE world_id=? AND id=1", id)
        val before = storedSuccessionState(id)
        val world = InMemoryTurnWorld(fixture.load(id))
        val recorder = ChangeRecorder()
        assertIs<TurnOutcome.Applied>(RetireHandler(world, recorder, DomesticContext(), deliveredCatalog())
            .handle(1, """{"successorGeneralId":2}""", null, null, npcSelected = true))
        assertTrue(recorder.generalOwnerDeletes().isEmpty())
        assertTrue(recorder.generalTurnClears().isEmpty())
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
        val after = storedSuccessionState(id)
        assertEquals(before.getValue("general_owner"), after.getValue("general_owner"))
        assertEquals(before.getValue("general_turn"), after.getValue("general_turn"))
    }

    @Test fun `queue clear failure rolls back retirement ownership identity cards assets and reservations atomically`() {
        val id = 782
        seedRenownWorld(id, 7)
        seedFullQueues(id)
        val before = storedSuccessionState(id)
        val clockBefore = jdbc.queryForList("SELECT * FROM world_state WHERE id=?", id)
        val world = InMemoryTurnWorld(fixture.load(id))
        val recorder = ChangeRecorder()
        assertIs<TurnOutcome.Applied>(RetireHandler(world, recorder, DomesticContext(), deliveredCatalog())
            .handle(1, """{"successorGeneralId":2}""", "retire-rollback-782", 42))
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        jdbc.execute("""CREATE FUNCTION synthetic_retire_clear_failure() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN IF OLD.world_id=782 AND OLD.general_id=1 THEN RAISE EXCEPTION 'synthetic retire clear failure'; END IF;
            RETURN OLD; END ${'$'}${'$'}""")
        jdbc.execute("CREATE TRIGGER synthetic_retire_clear_failure AFTER DELETE ON general_turn FOR EACH ROW EXECUTE FUNCTION synthetic_retire_clear_failure()")
        try {
            assertFailsWith<org.springframework.dao.DataAccessException> { flush.flush(payload) }
            assertEquals(before, storedSuccessionState(id))
            assertEquals(clockBefore, jdbc.queryForList("SELECT * FROM world_state WHERE id=?", id))
            val loaded = InMemoryTurnWorld(fixture.load(id))
            assertEquals("42", loaded.getGeneralById(1)!!.userId)
            assertNull(loaded.getGeneralById(2)!!.userId)
            assertFalse(loaded.getGeneralById(1)!!.meta["retired"] == true)
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_retire_clear_failure ON general_turn")
            jdbc.execute("DROP FUNCTION synthetic_retire_clear_failure()")
        }
    }

    @Test fun `excess rejection and retransmission preserve stored resources people cards reservations relations and ruler`() {
        val id = 778
        seedRenownWorld(id, 7)
        val request = RetireRequest(1, 2)
        val admitted = InMemoryTurnWorld(fixture.load(id))
        assertIs<RetireAssessment.Eligible>(RetireRules.assess(request, DomesticContext().projection(admitted)))
        // A durable change between admission and execution must be assessed from the current snapshot.
        setCapacity(id, 6)
        val before = storedSuccessionState(id)
        var world = InMemoryTurnWorld(fixture.load(id))
        val recorder = ChangeRecorder()
        for (requestId in listOf("retire-renown-778", "retire-renown-778", "retire-renown-778-new")) {
            assertEquals(RetireFailure.SUCCESSOR_RENOWN_EXCEEDED, assertIs<RetireAssessment.Rejected>(
                RetireRules.assess(request, DomesticContext().projection(world))).reason)
            assertEquals(RetireFailure.SUCCESSOR_RENOWN_EXCEEDED.name,
                assertIs<TurnOutcome.Rejected>(RetireHandler(world, recorder, DomesticContext(), deliveredCatalog())
                    .handle(1, """{"successorGeneralId":2}""", requestId, 42)).code)
            assertFalse(recorder.isDirty)
            flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
            assertEquals(before, storedSuccessionState(id))
            world = InMemoryTurnWorld(fixture.load(id))
            assertEquals(6, PersonPolicyState.read(world.getGeneralById(2)!!.meta)!!.renownCapacity)
        }
        // Equality and zero remain identical after a fresh load of the persisted cap.
        setCapacity(id, 7)
        assertIs<RetireAssessment.Eligible>(RetireRules.assess(request,
            DomesticContext().projection(InMemoryTurnWorld(fixture.load(id)))))
        setCapacity(id, 0)
        assertEquals(RetireFailure.SUCCESSOR_RENOWN_EXCEEDED, assertIs<RetireAssessment.Rejected>(
            RetireRules.assess(request, DomesticContext().projection(InMemoryTurnWorld(fixture.load(id))))).reason)
        jdbc.update("DELETE FROM general_retainers WHERE world_id=? AND id=5", id)
        assertIs<RetireAssessment.Eligible>(RetireRules.assess(request,
            DomesticContext().projection(InMemoryTurnWorld(fixture.load(id)))))
    }

    @Test fun `actual reserved retirement remains blocked and cannot move assets or erase another reservation`() {
        val id = 779
        seedRenownWorld(id, 0)
        val world = InMemoryTurnWorld(fixture.load(id))
        val before = storedSuccessionState(id)
        val recorder = ChangeRecorder()
        val handler = fixture.reservedHandler(world, recorder)
        val reserved = ReservedTurn(RetireInput.INPUT_ID, """{"successorGeneralId":2}""",
            requestId = "retire-planned-779", reservationOwnerUserId = 42)
        repeat(2) {
            val handled = handler.handle(1, reserved, 200, 1, "00:00")
            assertEquals(InputRejection.NOT_DELIVERED.name, assertIs<TurnOutcome.Rejected>(handled.inputOutcome).code)
        }
        assertFalse(recorder.isDirty)
        // The ordinary rejection record is allowed; succession rows and reservations remain identical.
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
        assertEquals(before, storedSuccessionState(id))
        val restored = InMemoryTurnWorld(fixture.load(id))
        assertEquals(RetireFailure.SUCCESSOR_RENOWN_EXCEEDED, assertIs<RetireAssessment.Rejected>(
            RetireRules.assess(RetireRequest(1, 2), DomesticContext().projection(restored))).reason)
    }

    @Test fun `persisted age boundary and ruler survive reload while planned rejection preserves identity and retinue`() {
        val id = 777
        fixture.seed(id)
        jdbc.update("UPDATE general SET nation_id=1 WHERE world_id=? AND id IN (1,2)", id)
        jdbc.update("UPDATE general SET user_id=42,age=59,officer_level=12 WHERE world_id=? AND id=1", id)
        val meta = CurrentRulerBinding.with(mapOf("gennum" to 3, "keep" to 42), 1, "synthetic-seed-777",
            CurrentRulerBinding.SCENARIO_SEED_SOURCE)
        jdbc.update("UPDATE nation SET meta=?::jsonb WHERE world_id=? AND id=1", MetaJson.encode(meta), id)
        for (generalId in listOf(1, 2)) jdbc.update("""INSERT INTO general_turn
            (world_id,general_id,turn_idx,action_code,arg,request_id)
            VALUES (?,?,1,?,'{"stat":"strength"}'::jsonb,?)""",
            id, generalId, PersonalInput.SELF_TRAIN, "synthetic-reservation-$generalId")
        val request = RetireRequest(1, 2)
        var world = InMemoryTurnWorld(fixture.load(id))
        assertEquals(59, world.getGeneralById(1)!!.age)
        assertEquals(1, world.getNationById(1)!!.chiefGeneralId)
        assertEquals(RetireFailure.AGE_TOO_YOUNG, assertIs<RetireAssessment.Rejected>(
            RetireRules.assess(request, DomesticContext().projection(world))).reason)

        jdbc.update("UPDATE general SET age=60 WHERE world_id=? AND id=1", id)
        world = InMemoryTurnWorld(fixture.load(id))
        assertEquals(60, world.getGeneralById(1)!!.age)
        assertIs<RetireAssessment.Eligible>(RetireRules.assess(request, DomesticContext().projection(world)))
        val generals = world.listGenerals()
        val retainers = world.listRetainers()
        val bugoks = world.listBugoks()
        val nation = world.getNationById(1)
        val reservations = jdbc.queryForList("SELECT * FROM general_turn WHERE world_id=? ORDER BY general_id,turn_idx", id)
        val recorder = ChangeRecorder()
        val denied = assertIs<TurnOutcome.Rejected>(RetireHandler(world, recorder, DomesticContext())
            .handle(1, """{"successorGeneralId":2}""", "retire-777", 42))
        assertEquals(InputRejection.NOT_DELIVERED.name, denied.code)
        assertFalse(recorder.isDirty)
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
        val restored = InMemoryTurnWorld(fixture.load(id))
        assertEquals(generals, restored.listGenerals())
        assertEquals(retainers, restored.listRetainers())
        assertEquals(bugoks, restored.listBugoks())
        assertEquals(nation, restored.getNationById(1))
        assertEquals(reservations,
            jdbc.queryForList("SELECT * FROM general_turn WHERE world_id=? ORDER BY general_id,turn_idx", id))
        assertIs<RetireAssessment.Eligible>(RetireRules.assess(request, DomesticContext().projection(restored)))
    }
}
