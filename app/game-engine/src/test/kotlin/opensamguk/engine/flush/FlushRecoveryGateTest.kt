package opensamguk.engine.flush

import opensamguk.common.world.WorldId
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.StaleWorldWriterException
import org.springframework.dao.QueryTimeoutException
import java.sql.SQLException
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlushRecoveryGateTest {

    private fun payload(): FlushPayload =
        // test helper lives in infra test sources — not on main classpath.
        // Build a minimal payload via FlushPayload directly if needed.
        FlushPayload(
            worldId = WorldId(1),
            worldStateUpdate = mapOf("id" to 1, "current_year" to 200, "current_month" to 1),
        )

    @Test
    fun `starts READY and allows intake`() {
        val gate = FlushRecoveryGate()
        assertEquals(FlushRecoveryGate.Mode.READY, gate.mode())
        assertTrue(gate.allowsIntakeOrTick())
        assertTrue(gate.snapshot().ready)
    }

    @Test
    fun `FLUSH_RETRY blocks intake and retains payload`() {
        val gate = FlushRecoveryGate()
        val p = payload()
        gate.enterFlushRetry(worldId = 1, generation = 7L, payload = p, reason = "timeout")
        assertEquals(FlushRecoveryGate.Mode.FLUSH_RETRY, gate.mode())
        assertFalse(gate.allowsIntakeOrTick())
        assertFalse(gate.snapshot().ready)
        assertNotNull(gate.retainedPayload())
        assertFailsWith<IllegalStateException> { gate.requireIntakeOrTickAllowed("tick") }
    }

    @Test
    fun `RELOAD_REQUIRED blocks intake and drops payload`() {
        val gate = FlushRecoveryGate()
        gate.enterFlushRetry(1, 1L, payload(), "x")
        gate.enterReloadRequired(worldId = 1, generation = 2L, reason = "stale")
        assertEquals(FlushRecoveryGate.Mode.RELOAD_REQUIRED, gate.mode())
        assertFalse(gate.allowsIntakeOrTick())
        assertNull(gate.retainedPayload())
    }

    @Test
    fun `markRecovered returns READY`() {
        val gate = FlushRecoveryGate()
        gate.enterFlushRetry(1, 3L, payload(), "retry")
        gate.markRecovered()
        assertTrue(gate.isReady())
        assertNull(gate.retainedPayload())
        assertNull(gate.snapshot().reason)
    }

    @Test
    fun `classify stale writer as RELOAD_REQUIRED`() {
        assertEquals(
            FlushRecoveryGate.Mode.RELOAD_REQUIRED,
            FlushRecoveryGate.classify(StaleWorldWriterException(1, 0L, 1L)),
        )
    }

    @Test
    fun `classify timeout and transient as FLUSH_RETRY`() {
        assertEquals(FlushRecoveryGate.Mode.FLUSH_RETRY, FlushRecoveryGate.classify(TimeoutException("x")))
        assertEquals(
            FlushRecoveryGate.Mode.FLUSH_RETRY,
            FlushRecoveryGate.classify(QueryTimeoutException("q")),
        )
    }

    @Test
    fun `classify bare SQLException as RELOAD_REQUIRED`() {
        assertEquals(
            FlushRecoveryGate.Mode.RELOAD_REQUIRED,
            FlushRecoveryGate.classify(SQLException("ambiguous")),
        )
    }
}
