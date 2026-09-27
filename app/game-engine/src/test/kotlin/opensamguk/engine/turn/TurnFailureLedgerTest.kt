package opensamguk.engine.turn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TurnFailureLedgerTest {
    @Test
    fun `three failures quarantine only the failing unit until the next month`() {
        val ledger = TurnFailureLedger()
        val failing = TurnFailureUnit.General(10)
        val other = TurnFailureUnit.General(11)

        assertEquals(1, ledger.recordFailure(failing, 2400).consecutiveFailures)
        assertEquals(2, ledger.recordFailure(failing, 2400).consecutiveFailures)
        assertEquals(TurnFailureState(3, 2401), ledger.recordFailure(failing, 2400))
        assertFalse(ledger.canExecute(failing, 2400))
        assertTrue(ledger.canExecute(other, 2400))
        assertFailsWith<IllegalArgumentException> { ledger.recordFailure(failing, 2400) }

        assertTrue(ledger.canExecute(failing, 2401))
        assertEquals(TurnFailureState(1), ledger.recordFailure(failing, 2401))
    }

    @Test
    fun `success resets only that unit's consecutive failure count`() {
        val ledger = TurnFailureLedger()
        val general = TurnFailureUnit.General(10)
        val envelope = TurnFailureUnit.Envelope("req-10")
        val monthStep = TurnFailureUnit.MonthlyStep("salary")

        ledger.recordFailure(general, 2400)
        ledger.recordFailure(envelope, 2400)
        ledger.recordFailure(monthStep, 2400)
        ledger.recordSuccess(general)

        assertNull(ledger.stateOf(general))
        assertEquals(TurnFailureState(1), ledger.recordFailure(general, 2400))
        assertEquals(TurnFailureState(2), ledger.recordFailure(envelope, 2400))
        assertEquals(TurnFailureState(1), ledger.stateOf(monthStep))
    }

    @Test
    fun `snapshot restores quarantine and cannot be mutated through its source`() {
        val unit = TurnFailureUnit.MonthlyStep("income")
        val first = TurnFailureLedger()
        repeat(3) { first.recordFailure(unit, 2400) }
        val saved = first.snapshot()
        first.recordSuccess(unit)

        val restored = TurnFailureLedger(saved)
        assertFalse(restored.canExecute(unit, 2400))
        assertTrue(restored.canExecute(unit, 2401))
        assertEquals(TurnFailureState(1), restored.recordFailure(unit, 2401))
    }

    @Test
    fun `restoring an impossible quarantine fails before it can disable retries`() {
        val unit = TurnFailureUnit.General(10)
        assertFailsWith<IllegalArgumentException> {
            TurnFailureLedger(mapOf(unit to TurnFailureState(2, 2401)))
        }
        assertFailsWith<IllegalArgumentException> {
            TurnFailureLedger(mapOf(unit to TurnFailureState(3, -1)))
        }
        assertFailsWith<IllegalArgumentException> {
            TurnFailureLedger(mapOf(unit to TurnFailureState(3)))
        }
        assertFailsWith<IllegalArgumentException> {
            TurnFailureLedger().recordFailure(unit, Int.MAX_VALUE)
        }
    }
}
