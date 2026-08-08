package opensamguk.engine.flush

import org.springframework.boot.actuate.health.Status
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * OPENSAM-175 — recovery 게이트가 헬스에 반영된다는 사실의 **단일 소유자**가 여기임을 고정한다.
 *
 * [opensamguk.engine.status.TurnDaemonHealthIndicator]는 recovery를 판정하지 않는다(중복 판정은 두
 * 인디케이터의 게이트 바인딩 시점 차이로 details가 모순돼 보이는 창만 만든다). 그 판정을 지우면서
 * "recovery-gated일 때 헬스가 UP이 아니다"를 증명할 책임이 이 테스트로 옮겨왔다.
 */
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
