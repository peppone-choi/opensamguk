package opensamguk.engine.status

import opensamguk.engine.boot.WorldStateAvailability
import opensamguk.engine.run.TurnClockSnapshot
import opensamguk.engine.run.TurnDaemonDiagnostics
import opensamguk.engine.run.TurnDaemonRunner
import opensamguk.engine.status.TurnDaemonHealthIndicator.Companion.STALE_TICK_MULTIPLIER
import opensamguk.engine.status.TurnDaemonHealthIndicator.Companion.evaluate
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `paused is UP so an intentional admin freeze never trips deploy or compose gates`() {
        // OUT_OF_SERVICE는 기본 매핑 503 → docker-compose healthcheck / predeploy_go_check / e2e 게이트를
        // 모두 깬다. 동결은 의도된 운영 상태이므로 UP + 상태 상세로 드러낸다.
        val health = evaluate(paused = true, diagnostics = diagnostics())
        assertEquals(Status.UP, health.status)
        assertEquals("paused", health.details["daemon"])
        assertEquals("동결중", health.details["statusLabel"])
    }

    @Test
    fun `paused daemon with a long stale tick stays UP`() {
        // 동결 중에는 틱이 안 도는 것이 정상 — 지연 판정을 건너뛴다.
        val health = evaluate(
            paused = true,
            diagnostics = diagnostics(lastSuccessfulTickAgeSeconds = tickSeconds * STALE_TICK_MULTIPLIER * 10),
        )
        assertEquals(Status.UP, health.status)
        assertEquals("paused", health.details["daemon"])
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
        val health = evaluate(
            paused = false,
            diagnostics = diagnostics(lastSuccessfulTickAgeSeconds = tickSeconds * STALE_TICK_MULTIPLIER),
        )
        assertEquals(Status.UP, health.status, "임계 미만(경계 포함) = 정상")
        assertEquals(tickSeconds * STALE_TICK_MULTIPLIER, health.details["staleSeconds"])
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
    fun `a daemon just resumed from a long freeze is UP until the threshold passes again`() {
        // 동결 중엔 틱이 안 도는 게 정상이라 lastTickCompletedAt이 동결 기간만큼 낡는다. 해제 직후 그
        // 값만 보면 다음 성공 틱(프로덕션 최대 1시간)까지 turn_stalled 거짓 DOWN — "위험 배포 전 동결 →
        // 배포 → 해제"라는 정상 운영 흐름이 배포 게이트를 도로 막는다.
        val frozenSeconds = 5 * 86_400L
        val justResumed = evaluate(
            paused = false,
            diagnostics = diagnostics(
                lastSuccessfulTickAgeSeconds = frozenSeconds,
                loopUptimeSeconds = frozenSeconds + 10,
            ),
            resumeAgeSeconds = 3,
        )
        assertEquals(Status.UP, justResumed.status, "해제 직후는 다음 틱을 기다리는 정상 상태")
        assertEquals("running", justResumed.details["daemon"])
        assertEquals(3L, justResumed.details["staleSeconds"], "기준점은 마지막 락풀기")

        // 유예는 딱 3틱 주기다 — 해제 후에도 틱이 안 돌면 여전히 잡아야 한다.
        val stillStalled = evaluate(
            paused = false,
            diagnostics = diagnostics(
                lastSuccessfulTickAgeSeconds = frozenSeconds,
                loopUptimeSeconds = frozenSeconds + 10,
            ),
            resumeAgeSeconds = tickSeconds * STALE_TICK_MULTIPLIER + 1,
        )
        assertEquals(Status.DOWN, stillStalled.status, "해제 후 임계 초과 = 진짜 고장")
        assertEquals("turn_stalled", stillStalled.details["daemon"])
    }

    @Test
    fun `a daemon restarted after a long stop is UP until the threshold passes again`() {
        // stop()은 lastTickCompletedAt을 일부러 보존한다(사실 기록). 그것만 보면 멀쩡한 새 루프가 첫
        // 성공 틱 전까지 즉시 turn_stalled다 — 기준점에 loopStartedAt을 포함해 닫는다.
        val stoppedSeconds = 5 * 86_400L
        val justRestarted = evaluate(
            paused = false,
            diagnostics = diagnostics(lastSuccessfulTickAgeSeconds = stoppedSeconds, loopUptimeSeconds = 3),
        )
        assertEquals(Status.UP, justRestarted.status, "재기동 직후는 다음 틱을 기다리는 정상 상태")
        assertEquals(3L, justRestarted.details["staleSeconds"], "기준점은 루프 기동 시각")

        val stillStalled = evaluate(
            paused = false,
            diagnostics = diagnostics(
                lastSuccessfulTickAgeSeconds = stoppedSeconds,
                loopUptimeSeconds = tickSeconds * STALE_TICK_MULTIPLIER + 1,
            ),
        )
        assertEquals(Status.DOWN, stillStalled.status, "재기동 후 임계 초과 = 진짜 고장")
        assertEquals("turn_stalled", stillStalled.details["daemon"])
    }

    @Test
    fun `the pause gate records only a real unfreeze transition`() {
        val gate = DaemonPauseGate()
        assertNull(gate.secondsSinceResume(), "동결된 적이 없으면 유예도 없다")

        gate.unlock()
        assertNull(gate.secondsSinceResume(), "가동중에 부른 락풀기는 no-op — 유예 재발급 금지")

        assertTrue(gate.lock())
        gate.unlock()
        assertNotNull(gate.secondsSinceResume(), "실제 동결→가동 전이만 기록된다")

        gate.restore(true)
        assertNull(gate.secondsSinceResume(), "부팅 시 durable plock 복원은 전이가 아니다")
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

    /**
     * `@Component` 빈 등록 + 생성자 주입 + [TurnDaemonHealthIndicator.health] 실경로를 증명한다.
     * Docker 없이도 반드시 도는 non-container 컨텍스트여야 증명이 성립하므로 [ApplicationContextRunner]로
     * `opensamguk.engine.status` 패키지만 실제 컴포넌트 스캔한다([GameEngineApplicationTests]는
     * Testcontainers 게이트라 Docker 부재 시 skip된다).
     */
    @Test
    fun `the indicator is registered as a component and its health path runs`() {
        ApplicationContextRunner()
            // `@Value("${...}")` 해석에 필요한 PropertySourcesPlaceholderConfigurer는 auto-config로 올린다.
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withPropertyValues("opensamguk.profile=che:test", "opensamguk.daemon.enabled=false")
            .withBean(WorldStateAvailability::class.java, { WorldStateAvailability { false } })
            // TurnDaemonRunner는 생성자 오토와이어로 등록한다(ObjectProvider<TurnRunService>는 빈이 없어도
            // 스프링이 빈 프로바이더로 만족시킨다). run 패키지를 스캔하면 실제 TurnRunService까지 끌려온다.
            .withBean(TurnDaemonRunner::class.java)
            .withUserConfiguration(StatusPackageScan::class.java)
            .run { context ->
                assertTrue(context.startupFailure == null, "context failure: ${context.startupFailure}")
                val indicator = context.getBean(TurnDaemonHealthIndicator::class.java)
                val health = indicator.health()
                assertNotNull(health.status)
                assertEquals("disabled", health.details["daemon"], "enabled=false 컨텍스트의 실제 판정")
            }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan("opensamguk.engine.status")
    class StatusPackageScan
}
