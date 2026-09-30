package opensamguk.engine.turn

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks

class TurnFailureLedgerCodecTest {
    private val states = linkedMapOf<TurnFailureUnit, TurnFailureState>(
        TurnFailureUnit.MonthlyStep("countyIncome") to TurnFailureState(3, 2401),
        TurnFailureUnit.General(7) to TurnFailureState(2),
        TurnFailureUnit.Envelope("request-9") to TurnFailureState(1),
    )

    @Test
    fun `round trip has stable order and rejects duplicate or invalid state`() {
        val encoded = checkNotNull(TurnFailureLedgerCodec.encode(states))
        val units = encoded["units"] as List<*>
        assertEquals(listOf("envelope", "general", "monthlyStep"), units.map { (it as Map<*, *>)["kind"] })
        assertEquals(states, TurnFailureLedgerCodec.decode(mapOf(TurnFailureLedgerCodec.META_KEY to encoded)))
        assertFailsWith<IllegalArgumentException> {
            TurnFailureLedgerCodec.decodeValue(mapOf("schemaVersion" to 1, "units" to units + units.first()))
        }
        assertFailsWith<IllegalArgumentException> {
            TurnFailureLedgerCodec.encode(mapOf(TurnFailureUnit.General(1) to TurnFailureState(3)))
        }
    }

    @Test
    fun `recorder flush channel is absent on clean baseline and restores at savepoint`() {
        val world = InMemoryTurnWorld(WorldSnapshot(
            state = TurnWorldState(1, 200, 1, 3600, Instant.parse("0200-01-01T00:00:00Z")),
            worldId = WorldId(1),
        ))
        val recorder = ChangeRecorder()
        fun payload() = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        assertFalse("turn_failure_ledger" in payload().worldStateUpdate)

        recorder.recordTurnFailureLedger(states)
        val checkpoint = recorder.checkpoint()
        recorder.recordTurnFailureLedger(emptyMap())
        assertTrue("turn_failure_ledger" in payload().worldStateUpdate)
        assertNull(payload().worldStateUpdate["turn_failure_ledger"])
        recorder.restore(checkpoint)
        val encoded = payload().worldStateUpdate["turn_failure_ledger"]
        assertEquals(states, TurnFailureLedgerCodec.decodeValue(encoded))
        recorder.clear()
        assertFalse(recorder.isDirty)
    }
}
