package opensamguk.engine.flush

import org.springframework.boot.actuate.health.Status
import kotlin.test.Test
import kotlin.test.assertEquals

class FlushRecoveryHealthIndicatorTest {

    private fun indicatorFor(gate: FlushRecoveryGate?): FlushRecoveryHealthIndicator =
        FlushRecoveryHealthIndicator(FlushRecoveryGateProvider().apply { gate?.let { bind(it) } })

    @Test
    fun `RELOAD_REQUIRED world is DOWN`() {
        val gate = FlushRecoveryGate().apply {
            enterReloadRequired(worldId = 1, generation = 2L, reason = "stale world writer fence")
        }
        val health = indicatorFor(gate).health()
        assertEquals(Status.DOWN, health.status, "2026-08 사고 모드 — 헬스가 UP이면 안 된다")
        assertEquals("RELOAD_REQUIRED", health.details["mode"])
        assertEquals("stale world writer fence", health.details["reason"])
    }

    @Test
    fun `a ready world is UP`() {
        val health = indicatorFor(FlushRecoveryGate()).health()
        assertEquals(Status.UP, health.status)
        assertEquals("READY", health.details["mode"])
    }

    @Test
    fun `no bound gate is UP`() {
        val health = indicatorFor(null).health()
        assertEquals(Status.UP, health.status)
        assertEquals("no_world", health.details["recovery"])
    }
}
