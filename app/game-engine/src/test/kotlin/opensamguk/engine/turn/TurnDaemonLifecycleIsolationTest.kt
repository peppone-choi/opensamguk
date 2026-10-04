package opensamguk.engine.turn

import java.time.Instant
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.engine.campaign.BattleOutcomeObservation
import opensamguk.engine.campaign.BattleOutcomePostFlush
import opensamguk.engine.campaign.TurnOutcome
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.event.EventStore
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.dao.DataAccessResourceFailureException

class TurnDaemonLifecycleIsolationTest {
    private val start = Instant.parse("0200-01-01T00:00:00Z")

    private fun general(id: Int) = TurnGeneral(
        id = id, name = "장수$id", nationId = 0, cityId = 1, troopId = 0,
        stats = GeneralStats(60, 60, 60), experience = 0, dedication = 0,
        officerLevel = 0, turnTime = start,
    )

    private fun world(vararg generals: TurnGeneral, meta: Map<String, Any?> = emptyMap()) =
        InMemoryTurnWorld(WorldSnapshot(
            state = TurnWorldState(1, 200, 1, 3600, start,
                config = mapOf("mapName" to "han-world-v3", "ruleProfile" to "HWIHA"), meta = meta),
            worldId = WorldId(1), generals = generals.toList(), cities = listOf(City(1, "성", 0, 1)),
        ))

    private fun handler(world: InMemoryTurnWorld, recorder: ChangeRecorder) = lifecycleTestHandler(world, recorder)

    @Test
    fun `damaged personal stamp reaches only its own failure unit and scheduler still advances`() {
        val damaged = general(1).copy(meta = mapOf("lastPersonalTurn" to "damaged"))
        val world = world(damaged, general(2))
        val recorder = ChangeRecorder()
        val lifecycle = TurnDaemonLifecycle(world, handler(world, recorder),
            reservedActionOf = { ReservedTurn("휴식", "{}", rowExists = false) },
        )

        assertEquals(start.plusNanos(1), lifecycle.nextGeneralRunTime())
        val handled = lifecycle.runTick(start.plusSeconds(1))
        assertEquals(listOf(1, 2), handled.map { it.generalId })
        assertEquals("EXECUTION_FAILED", assertIs<TurnOutcome.Rejected>(handled.first().inputOutcome).code)
        assertTrue("lastPersonalTurn" in checkNotNull(world.getGeneralById(1)).meta)
        assertTrue(lifecycle.runTick(start.plusSeconds(3601)).isEmpty())
        assertEquals(1, TurnFailureLedgerCodec.decodeValue(recorder.turnFailureLedgerWrite()?.payload)
            .getValue(TurnFailureUnit.General(1)).consecutiveFailures)
    }

    @Test
    fun `database failure inside a unit reaches recovery without consuming reservation or clock`() {
        val world = world(general(1), general(2))
        val recorder = ChangeRecorder()
        val lifecycle = TurnDaemonLifecycle(world, handler(world, recorder),
            npcInputOf = { _, _ -> throw DataAccessResourceFailureException("database unavailable") },
            reservedActionOf = { ReservedTurn("action.enlist", "{}", requestId = "req-1") },
        )

        assertFailsWith<DataAccessResourceFailureException> { lifecycle.runTick(start.plusSeconds(1)) }
        assertTrue(recorder.reservedGeneralTurnPulls().isEmpty())
        assertNull(recorder.turnFailureLedgerWrite())
        assertEquals(start, world.getGeneralById(1)?.turnTime)
        assertEquals(start, world.getGeneralById(2)?.turnTime)
    }

    @Test
    fun `wrapped SQL failure also reaches recovery without recording a unit failure`() {
        val world = world(general(1))
        val recorder = ChangeRecorder()
        val lifecycle = TurnDaemonLifecycle(world, handler(world, recorder),
            npcInputOf = { _, _ -> throw IllegalStateException("wrapped SQL", SQLException("connection lost")) },
            reservedActionOf = { ReservedTurn("action.enlist", "{}", requestId = "req-1") },
        )

        assertFailsWith<IllegalStateException> { lifecycle.runTick(start.plusSeconds(1)) }
        assertTrue(recorder.reservedGeneralTurnPulls().isEmpty())
        assertNull(recorder.turnFailureLedgerWrite())
        assertEquals(start, world.getGeneralById(1)?.turnTime)
    }

    @Test
    fun `failed general rolls back world recorder event rows and battle outbox while next general advances`() {
        val world = world(general(1), general(2))
        val recorder = ChangeRecorder(kvWriteObserver = world::applyKvDirtyFree)
        val handler = handler(world, recorder)
        val events = EventStore.withDefaults()
        events.bindMutationSink(recorder::recordEventMutation)
        val originalEvents = events.allRows()
        val published = mutableListOf<String>()
        val battles = BattleOutcomePostFlush { published += it.observations.map { row -> row.encounterId } }
        val observation = mock(BattleOutcomeObservation::class.java)
        `when`(observation.worldId).thenReturn(1)
        `when`(observation.encounterId).thenReturn("a".repeat(64))
        val lifecycle = TurnDaemonLifecycle(world, handler,
            unitExecutor = TurnUnitExecutor(world, recorder, events), battleOutcomePostFlush = battles,
            npcInputOf = { id, reserved ->
                if (id == 1) {
                    world.updateGeneral(checkNotNull(world.getGeneralById(1)).copy(gold = 900))
                    recorder.recordKv("game_env", "game_env", "failed", 1)
                    events.delete(originalEvents.first().id)
                    battles.onResolved(observation)
                    throw IllegalStateException("비밀 원문을 사용자에게 노출하지 않는다")
                }
                reserved
            },
            reservedActionOf = { id -> if (id == 1) ReservedTurn("action.enlist", "{}", requestId = "req-1")
                else ReservedTurn("휴식", "{}", rowExists = false) },
        )

        val handled = lifecycle.runTick(start.plusSeconds(1))
        assertEquals(listOf(1, 2), handled.map { it.generalId })
        val rejected = assertIs<TurnOutcome.Rejected>(handled.first().inputOutcome)
        assertEquals("EXECUTION_FAILED", rejected.code)
        assertFalse("비밀" in rejected.reason)
        assertEquals("req-1", handled.first().requestId)
        assertEquals(0, world.getGeneralById(1)?.gold)
        assertEquals(start.plusSeconds(3600), world.getGeneralById(1)?.turnTime)
        assertEquals(start.plusSeconds(3600), world.getGeneralById(2)?.turnTime)
        assertFalse(KvKey("game_env", "game_env", "failed") in recorder.kvDirty())
        assertEquals(originalEvents, events.allRows())
        battles.afterSuccessfulFlush(1, 1)
        assertTrue(published.isEmpty())
        assertEquals(1, recorder.reservedGeneralTurnPulls().count { it.generalId == 1 })
        assertEquals(1, TurnFailureLedgerCodec.decodeValue(recorder.turnFailureLedgerWrite()?.payload)
            .getValue(TurnFailureUnit.General(1)).consecutiveFailures)
    }

    @Test
    fun `third failure quarantines only that general until next month and cold ledger restores`() {
        val world = world(general(1), general(2))
        val recorder = ChangeRecorder()
        var attempts = 0
        val lifecycle = TurnDaemonLifecycle(world, handler(world, recorder),
            npcInputOf = { id, reserved ->
                if (id == 1) {
                    attempts++
                    if (attempts <= 3) throw IllegalStateException("secret")
                }
                reserved
            },
            reservedActionOf = { ReservedTurn("휴식", "{}", rowExists = false) },
        )
        repeat(3) { phase ->
            world.setCurrentDate(200, 1, phase + 1)
            val result = lifecycle.runTick(start.plusSeconds(phase * 3600L + 1))
            assertEquals("EXECUTION_FAILED", assertIs<TurnOutcome.Rejected>(result.first().inputOutcome).code)
            assertTrue(lifecycle.runTick(start.plusSeconds((phase + 1) * 3600L + 1)).none { it.generalId == 1 })
        }
        val saved = checkNotNull(recorder.turnFailureLedgerWrite()?.payload)
        assertEquals(TurnFailureState(3, 2401), TurnFailureLedgerCodec.decodeValue(saved)[TurnFailureUnit.General(1)])
        assertEquals(3, attempts)
        assertEquals(start.plusSeconds(3 * 3600L), world.getGeneralById(1)?.turnTime)
        assertEquals(start.plusSeconds(3 * 3600L), world.getGeneralById(2)?.turnTime)

        val cold = world(general(1).copy(turnTime = start.plusSeconds(3 * 3600L)),
            meta = mapOf(TurnFailureLedgerCodec.META_KEY to saved))
        cold.setCurrentDate(200, 1, 3)
        val coldRecorder = ChangeRecorder()
        var coldAttempts = 0
        val restored = TurnDaemonLifecycle(cold, handler(cold, coldRecorder),
            npcInputOf = { _, reserved -> coldAttempts++; reserved },
            reservedActionOf = { ReservedTurn("휴식", "{}", rowExists = false) },
        )
        assertEquals("TURN_QUARANTINED", assertIs<TurnOutcome.Rejected>(
            restored.runTick(start.plusSeconds(3 * 3600L + 1)).single().inputOutcome).code)
        assertEquals(0, coldAttempts)
        cold.setCurrentDate(200, 2, 1)
        val resumed = restored.runTick(start.plusSeconds(4 * 3600L + 1)).single()
        assertNull(resumed.requestId)
        assertEquals(1, coldAttempts)
        assertNull(TurnFailureLedgerCodec.decodeValue(coldRecorder.turnFailureLedgerWrite()?.payload)
            [TurnFailureUnit.General(1)])
    }
}
