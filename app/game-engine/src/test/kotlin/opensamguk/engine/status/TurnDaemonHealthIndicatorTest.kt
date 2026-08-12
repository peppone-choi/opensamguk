package opensamguk.engine.status

import opensamguk.engine.run.TurnClockSnapshot
import opensamguk.engine.run.TurnDaemonDiagnostics
import opensamguk.engine.status.TurnDaemonHealthIndicator.Companion.STALE_TICK_MULTIPLIER
import opensamguk.engine.status.TurnDaemonHealthIndicator.Companion.evaluate
import org.springframework.boot.actuate.health.Status
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * OPENSAM-175 — 프로세스 생존만으로 UP이 되던 관측 공백 회귀 방지.
 *
 * 2026-08-05~08-08 사고 재현: 데몬이 `RELOAD_REQUIRED`로 차단된 채 컨테이너는 `Up`이었고 헬스는 UP이었다.
 */
class TurnDaemonHealthIndicatorTest {

    private val tickSeconds = 3600

    /** 게임 클럭 — 지연 판정에는 `tickSeconds`만 쓰인다(`lastTurnTime`은 표시용). */
    private fun clock(
        lastTurnTime: Instant = Instant.parse("2026-08-08T00:00:00Z"),
        tickSeconds: Int = this.tickSeconds,
    ) = TurnClockSnapshot(
        currentYear = 190,
        currentMonth = 3,
        currentPhase = 0,
        currentPhaseText = "turn",
        tickSeconds = tickSeconds,
        lastTurnTime = lastTurnTime.toString(),
        nextRunTime = lastTurnTime.plusSeconds(tickSeconds.toLong()).toString(),
    )

    private fun diagnostics(
        clock: TurnClockSnapshot? = clock(),
        serviceMaterialized: Boolean = true,
        autoStartEnabled: Boolean = true,
        lastSuccessfulTickAgeSeconds: Long? = 5,
        // 루프는 마지막 성공 틱보다 반드시 먼저 떠 있었다. 고정 10초를 기본값으로 두면 "2.3일째 틱이
        // 없는데 루프는 10초 전에 떴다"처럼 존재할 수 없는 픽스처가 되고, 그건 재기동 케이스지 고장이
        // 아니다. 재기동/부팅 케이스는 호출부가 짧은 uptime을 명시적으로 준다.
        loopUptimeSeconds: Long? = (lastSuccessfulTickAgeSeconds ?: 0) + 10,
        clockError: String? = null,
        recoveryReady: Boolean = true,
        recoveryMode: String? = "READY",
    ) = TurnDaemonDiagnostics(
        serviceMaterialized = serviceMaterialized,
        clock = clock,
        lastTickStartedAt = null,
        // 실제 [TurnDaemonRunner.diagnostics]는 lastSuccessfulTickAgeSeconds를 이 필드에서 파생한다
        // (TurnDaemonRunner.kt) — 둘을 따로 놓으면 존재할 수 없는 상태의 픽스처가 된다.
        lastTickCompletedAt = lastSuccessfulTickAgeSeconds
            ?.let { Instant.now().minusSeconds(it).toString() },
        lastTickFailedAt = null,
        lastTickError = null,
        successfulTicks = 0,
        failedTicks = 0,
        consecutiveFailures = 0,
        autoStartEnabled = autoStartEnabled,
        loopUptimeSeconds = loopUptimeSeconds,
        lastSuccessfulTickAgeSeconds = lastSuccessfulTickAgeSeconds,
        clockError = clockError,
        recoveryReady = recoveryReady,
        recoveryMode = recoveryMode,
    )

    @Test
    fun `catch-up with a days-old game clock but a fresh wall-clock tick is UP`() {
        // 회귀의 핵심: TurnRunService.kt:489가 심는 lastTurnTime은 게임 스케줄 시각이라 캐치업 중에는
        // 며칠 뒤처진 채 틱마다 tickSeconds씩만 전진한다. 데몬은 8초/턴으로 정상 작동 중이다.
        val daysBehind = clock(lastTurnTime = Instant.parse("2026-08-05T16:15:45Z"))
        val health = evaluate(
            paused = false,
            diagnostics = diagnostics(clock = daysBehind, lastSuccessfulTickAgeSeconds = 8),
        )
        assertEquals(Status.UP, health.status, "캐치업은 정상 가동 — 게임 클럭 지연으로 오탐하면 안 된다")
        assertEquals("running", health.details["daemon"])
    }

    @Test
    fun `paused is OUT_OF_SERVICE so an intentional admin freeze is operationally visible`() {
        val health = evaluate(paused = true, diagnostics = diagnostics())
        assertEquals(Status.OUT_OF_SERVICE, health.status)
        assertEquals("paused", health.details["daemon"])
        assertEquals("동결중", health.details["statusLabel"])
        assertEquals(clock().lastTurnTime, health.details["lastTurnTime"])
    }

    @Test
    fun `paused daemon with a long stale tick remains OUT_OF_SERVICE rather than becoming silently UP`() {
        // 동결 중에는 틱이 안 도는 것이 정상 — 지연 판정을 건너뛴다.
        val health = evaluate(
            paused = true,
            diagnostics = diagnostics(lastSuccessfulTickAgeSeconds = tickSeconds * STALE_TICK_MULTIPLIER * 10),
        )
        assertEquals(Status.OUT_OF_SERVICE, health.status)
        assertEquals("paused", health.details["daemon"])
    }

    @Test
    fun `recovery-gated daemon is DOWN even when an admin pause is also active`() {
        val health = evaluate(
            paused = true,
            diagnostics = diagnostics(recoveryReady = false, recoveryMode = "RELOAD_REQUIRED"),
        )
        assertEquals(Status.DOWN, health.status)
        assertEquals("recovery_gated", health.details["daemon"])
        assertEquals("RELOAD_REQUIRED", health.details["recoveryMode"])
        assertEquals(clock().lastTurnTime, health.details["lastTurnTime"])
    }

    @Test
    fun `recovery-gated health bounds an unrecognized recovery mode`() {
        val health = evaluate(
            paused = false,
            diagnostics = diagnostics(recoveryReady = false, recoveryMode = "secret-sentinel"),
        )
        assertEquals(Status.DOWN, health.status)
        assertEquals("recovery_gated", health.details["daemon"])
        assertEquals("UNKNOWN", health.details["recoveryMode"])
    }

    @Test
    fun `a paused daemon still reports a broken clock and an invalid tick_seconds`() {
        // 동결은 "턴이 안 도는 게 정상"일 뿐 — 클럭 고장/설정 이상까지 덮으면 해제 시점까지 은폐된다.
        val unreadable = evaluate(
            paused = true,
            diagnostics = diagnostics(clockError = "java.lang.IllegalStateException: boom"),
        )
        assertEquals(Status.DOWN, unreadable.status, "동결 중이라도 클럭 고장은 고장")
        assertEquals("clock_unavailable", unreadable.details["daemon"])

        val misconfigured = evaluate(paused = true, diagnostics = diagnostics(clock = clock(tickSeconds = 0)))
        assertEquals(Status.DOWN, misconfigured.status, "동결 중이라도 설정 이상은 고장")
        assertEquals("tick_seconds_invalid", misconfigured.details["daemon"])
    }

    @Test
    fun `wall-clock tick age exactly at the threshold is still healthy`() {
        val shortTickSeconds = 17
        val health = evaluate(
            paused = false,
            diagnostics = diagnostics(
                clock = clock(tickSeconds = shortTickSeconds),
                lastSuccessfulTickAgeSeconds = shortTickSeconds * STALE_TICK_MULTIPLIER,
            ),
        )
        assertEquals(Status.UP, health.status, "임계 미만(경계 포함) = 정상")
        assertEquals(shortTickSeconds * STALE_TICK_MULTIPLIER, health.details["staleSeconds"])
        assertEquals(shortTickSeconds * STALE_TICK_MULTIPLIER, health.details["allowedSeconds"])
    }

    @Test
    fun `wall-clock tick age one second past the threshold is DOWN`() {
        val health = evaluate(
            paused = false,
            diagnostics = diagnostics(lastSuccessfulTickAgeSeconds = tickSeconds * STALE_TICK_MULTIPLIER + 1),
        )
        assertEquals(Status.DOWN, health.status)
        assertEquals("turn_stalled", health.details["daemon"])
        assertEquals(false, health.details["neverTicked"])
    }

    @Test
    fun `the 2 point 3 day production freeze is DOWN`() {
        // 데몬이 RELOAD_REQUIRED로 막혀 벽시계 기준 2.3일간 성공 틱이 없었다.
        val frozenSeconds = 2 * 86_400L + 28_000L
        val health = evaluate(
            paused = false,
            diagnostics = diagnostics(lastSuccessfulTickAgeSeconds = frozenSeconds),
        )
        assertEquals(Status.DOWN, health.status)
        assertEquals("turn_stalled", health.details["daemon"])
    }

    @Test
    fun `resuming does not reset the age of the last successful tick`() {
        val frozenSeconds = 5 * 86_400L
        val justResumed = evaluate(
            paused = false,
            diagnostics = diagnostics(
                lastSuccessfulTickAgeSeconds = frozenSeconds,
                loopUptimeSeconds = frozenSeconds + 10,
            ),
        )
        assertEquals(Status.DOWN, justResumed.status, "해제는 이미 늙은 성공 틱의 벽시계 age를 리셋하지 않는다")
        assertEquals("turn_stalled", justResumed.details["daemon"])
        assertEquals(frozenSeconds, justResumed.details["staleSeconds"])
    }

    @Test
    fun `restarting does not reset the age of the last successful tick`() {
        val stoppedSeconds = 5 * 86_400L
        val justRestarted = evaluate(
            paused = false,
            diagnostics = diagnostics(lastSuccessfulTickAgeSeconds = stoppedSeconds, loopUptimeSeconds = 3),
        )
        assertEquals(Status.DOWN, justRestarted.status, "재기동은 이미 늙은 성공 틱의 벽시계 age를 리셋하지 않는다")
        assertEquals("turn_stalled", justRestarted.details["daemon"])
        assertEquals(stoppedSeconds, justRestarted.details["staleSeconds"])
    }

    @Test
    fun `a loop that never ticked past the threshold is DOWN even during boot grace`() {
        val health = evaluate(
            paused = false,
            diagnostics = diagnostics(
                lastSuccessfulTickAgeSeconds = null,
                loopUptimeSeconds = tickSeconds * STALE_TICK_MULTIPLIER + 1,
            ),
        )
        assertEquals(Status.DOWN, health.status, "루프 살아있고 월드도 있는데 3틱 주기 동안 성공 틱 0 = 고장")
        assertEquals(true, health.details["neverTicked"])
    }

    @Test
    fun `a freshly started loop that has not ticked yet is UP`() {
        val health = evaluate(
            paused = false,
            diagnostics = diagnostics(lastSuccessfulTickAgeSeconds = null, loopUptimeSeconds = 2),
        )
        assertEquals(Status.UP, health.status, "부팅 직후 유예 — 루프 기동 시각 기준")
        assertEquals(true, health.details["neverTicked"])
    }

    @Test
    fun `an enabled daemon whose loop never came up is DOWN`() {
        // opensamguk.daemon.enabled=true인데 SmartLifecycle 루프가 없다 = "돌아야 하는데 안 뜬" 상태.
        val health = evaluate(paused = false, diagnostics = diagnostics(loopUptimeSeconds = null))
        assertEquals(Status.DOWN, health.status)
        assertEquals("loop_not_running", health.details["daemon"])
    }

    @Test
    fun `a deliberately disabled daemon is UP but says so`() {
        val health = evaluate(
            paused = false,
            diagnostics = diagnostics(autoStartEnabled = false, loopUptimeSeconds = null),
        )
        assertEquals(Status.UP, health.status)
        assertEquals("disabled", health.details["daemon"], "의도적 off는 본문에 드러나야 한다")
    }

    @Test
    fun `daemon that has not materialized a world is UP`() {
        val health = evaluate(paused = false, diagnostics = diagnostics(serviceMaterialized = false))
        assertEquals(Status.UP, health.status, "월드 미생성/부팅 중은 장애가 아니다")
        assertEquals("not_started", health.details["daemon"])
    }

    @Test
    fun `an unreadable clock is reported separately from an invalid tick_seconds`() {
        val unreadable = evaluate(
            paused = false,
            diagnostics = diagnostics(clock = clock(tickSeconds = 0), clockError = "java.lang.IllegalStateException: boom"),
        )
        assertEquals(Status.DOWN, unreadable.status)
        assertEquals("clock_unavailable", unreadable.details["daemon"])

        val misconfigured = evaluate(paused = false, diagnostics = diagnostics(clock = clock(tickSeconds = 0)))
        assertEquals(Status.DOWN, misconfigured.status)
        assertEquals("tick_seconds_invalid", misconfigured.details["daemon"], "설정 이상과 클럭 고장은 다른 고장")
        assertEquals(0, misconfigured.details["tickSeconds"])
    }

}
