package opensamguk.engine.status

import opensamguk.engine.run.TurnDaemonDiagnostics
import opensamguk.engine.run.TurnDaemonRunner
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.health.Status
import org.springframework.stereotype.Component

/**
 * OPENSAM-175 — 턴 데몬이 자기 상태를 헬스에 정직하게 드러낸다.
 *
 * 사고(2026-08-05 ~ 08-08, 2.3일): 데몬이 `RELOAD_REQUIRED`로 영구 차단됐는데 프로세스는 살아 있어
 * 컨테이너 `Up` + `/api/health` `{"status":"UP"}` 였고 어떤 트리거도 걸리지 않았다. 프로세스 생존은
 * 데몬 가동의 증거가 아니다.
 *
 * 판정(위에서부터 우선):
 *  - `opensamguk.daemon.enabled=false` → UP + `daemon=disabled`. 의도적으로 끈 상태다(`@SpringBootTest`
 *    컨텍스트 로드도 이 값을 쓴다). 다만 프로덕션 오설정이 조용히 묻히지 않도록 상태를 본문에 명시한다.
 *  - 켜져 있는데 루프 스레드가 없음 → DOWN. **"돌아야 하는데 안 뜬"** 상태다(start 실패/조기 stop).
 *    부팅 중에는 [TurnDaemonRunner.getPhase]가 `Int.MAX_VALUE`라 웹서버 기동 후 잠시 이 상태일 수 있는데,
 *    `docker-compose.yml`의 `start_period: 90s`가 그 창을 덮는다.
 *  - 루프는 살아 있으나 월드 미생성(`world_state` 비어 서버 생성 대기) → UP. 아직 돌 것이 없다
 *    ([StatusController]의 `state="idle"`과 같은 상태).
 *  - 클럭 조회 실패 → DOWN `clock_unavailable`. 설정 이상(`tickSeconds<=0`) → DOWN `tick_seconds_invalid`.
 *    서로 다른 고장이므로 뭉개지 않는다. 동결과 무관한 고장이라 아래 `paused`보다 **먼저** 본다.
 *  - recovery gate가 준비되지 않음 → DOWN `recovery_gated`. 정상 플러시 재시도/재로드 전에는 턴을
 *    재개할 수 없으므로 paused보다 우선하는 fail-closed 상태다.
 *  - `paused`(동결, PHP `plock>0`) → OUT_OF_SERVICE + `daemon=paused`. 의도된 운영 상태이지만 자동
 *    관측자가 무조건 UP으로 오인하면 안 된다. 동결 중에는 턴이 안 도는 것이 정상이므로 아래 지연 판정을
 *    건너뛴다.
 *  - **벽시계** 기준 지연 → DOWN `turn_stalled`. 데몬이 어떤 이유로 멈추든 잡히는 상위 지표다. 기준점은
 *    마지막 성공 틱 이후의 경과 초다. 한 번도 성공하지 못한 새 루프에만 루프 기동 시각을 사용한다.
 *
 * Recovery has a dedicated flush indicator too, but this indicator repeats the bounded recovery state so the daemon
 * decision remains explicit at its own health surface rather than relying on composite-health inference.
 *
 * 비용: [TurnDaemonRunner.diagnostics]는 인메모리 필드만 읽는다(새 DB 읽기 없음). 턴 루프 hot path에는
 * 성공 시각 volatile 쓰기 한 번(이미 존재하던 `lastTickCompletedAt`) 외에 아무것도 더하지 않는다.
 */
@Component
class TurnDaemonHealthIndicator(
    private val pauseGate: DaemonPauseGate,
    private val runner: TurnDaemonRunner,
) : HealthIndicator {

    override fun health(): Health = evaluate(pauseGate.isPaused(), runner.diagnostics())

    companion object {
        /**
         * 지연 허용 배수 — `tick_seconds`의 몇 배까지를 정상으로 볼 것인가.
         *
         * 지표는 **벽시계 기준 마지막 성공 틱 이후 경과**다(게임 클럭 아님 — 캐치업 오탐 방지,
         * `TurnDaemonDiagnostics.lastSuccessfulTickAgeSeconds` 주석 참조). 정상 운영이면 틱은 정확히
         * `tick_seconds`마다, 캐치업이면 그보다 더 자주 성공하므로 이 값은 평시에 `tick_seconds`를 크게
         * 넘지 않는다. 3배는 느린 flush·긴 월간 파이프라인을 삼킬 여유라 지터로는 울리지
         * 않으면서(오탐 방지), 프로덕션 기본 `tick_seconds=3600`(1시간=1턴) 기준 영구 차단을 3시간 안에
         * 잡는다 — 2.3일 방치와 자릿수가 다르다.
         * post-parity까지 상수 외부화는 유예한다.
         */
        const val STALE_TICK_MULTIPLIER: Long = 3

        fun evaluate(
            paused: Boolean,
            diagnostics: TurnDaemonDiagnostics,
        ): Health {
            if (!diagnostics.autoStartEnabled) {
                return Health.up().withDetail("daemon", "disabled").build()
            }
            val uptimeSeconds = diagnostics.loopUptimeSeconds
                ?: return Health.down().withDetail("daemon", "loop_not_running").build()
            if (!diagnostics.serviceMaterialized) {
                return Health.up().withDetail("daemon", "not_started").build()
            }
            // 클럭 고장/설정 이상은 동결 **판정보다 먼저** 본다. 동결은 "턴이 안 도는 게 정상"인 상태일 뿐이라
            // 지연 판정만 무의미해질 뿐, 클럭 조회 실패나 tick_seconds<=0은 동결과 무관한 고장이다.
            // paused를 앞에 두면 동결 중 발생한 이 고장들이 해제 시점까지 통째로 은폐된다.
            val clock = diagnostics.clock
            if (clock == null || diagnostics.clockError != null) {
                return Health.down()
                    .withDetail("daemon", "clock_unavailable")
                    .withDetail("clockError", diagnostics.clockError ?: "clock snapshot missing")
                    .build()
            }
            if (clock.tickSeconds <= 0) {
                return Health.down()
                    .withDetail("daemon", "tick_seconds_invalid")
                    .withDetail("tickSeconds", clock.tickSeconds)
                    .build()
            }
            val allowedSeconds = clock.tickSeconds.toLong() * STALE_TICK_MULTIPLIER
            if (!diagnostics.recoveryReady) {
                return Health.down()
                    .withDetail("daemon", "recovery_gated")
                    .withDetail("recoveryMode", boundedRecoveryMode(diagnostics.recoveryMode))
                    .withDetail("tickSeconds", clock.tickSeconds)
                    .withDetail("allowedSeconds", allowedSeconds)
                    .withDetail("lastTurnTime", clock.lastTurnTime)
                    .build()
            }
            if (paused) {
                return Health.status(Status.OUT_OF_SERVICE)
                    .withDetail("daemon", "paused")
                    // PHP `_119.php:36` 표시 라벨 verbatim.
                    .withDetail("statusLabel", "동결중")
                    .withDetail("tickSeconds", clock.tickSeconds)
                    .withDetail("allowedSeconds", allowedSeconds)
                    .withDetail("lastTurnTime", clock.lastTurnTime)
                    .build()
            }
            val neverTicked = diagnostics.lastSuccessfulTickAgeSeconds == null
            val ageSeconds = diagnostics.lastSuccessfulTickAgeSeconds ?: uptimeSeconds
            if (ageSeconds > allowedSeconds) {
                return Health.down()
                    .withDetail("daemon", "turn_stalled")
                    .withDetail("neverTicked", neverTicked)
                    .withDetail("staleSeconds", ageSeconds)
                    .withDetail("allowedSeconds", allowedSeconds)
                    .withDetail("lastTurnTime", clock.lastTurnTime)
                    .build()
            }
            return Health.up()
                .withDetail("daemon", "running")
                .withDetail("neverTicked", neverTicked)
                .withDetail("staleSeconds", ageSeconds)
                .withDetail("allowedSeconds", allowedSeconds)
                .withDetail("lastTurnTime", clock.lastTurnTime)
                .build()
        }

        private fun boundedRecoveryMode(mode: String?): String = when (mode) {
            "READY", "FLUSH_RETRY", "RELOAD_REQUIRED" -> mode
            else -> "UNKNOWN"
        }
    }
}
