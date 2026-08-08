package opensamguk.engine.status

import opensamguk.engine.run.TurnDaemonDiagnostics
import opensamguk.engine.run.TurnDaemonRunner
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
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
 *  - `paused`(동결, PHP `plock>0`) → **UP** + `daemon=paused`. 의도된 어드민 동결이며,
 *    `Status.OUT_OF_SERVICE`는 Spring Boot 기본 매핑이 **503**이라 `docker-compose.yml:203` 헬스체크와
 *    `tools/ops/predeploy_go_check.sh:170`(200 + `{"status":"UP"` 요구), `tools/e2e/local_v1_gate.sh:357`을
 *    모두 깬다 — 위험 배포 전 동결이 배포 게이트를 막는 자충수가 된다. 운영 상태 구분은
 *    [StatusController]가 `state="paused"`/`statusLabel="동결중"`으로 이미 정확히 노출한다.
 *    동결 중에는 턴이 안 도는 것이 정상이므로 아래 지연 판정을 건너뛴다.
 *  - **벽시계** 기준 지연 → DOWN `turn_stalled`. 데몬이 어떤 이유로 멈추든 잡히는 상위 지표다. 기준점은
 *    "데몬이 틱을 돌 수 있었던 가장 최근 시점" = max(마지막 성공 틱, 마지막 락풀기, 루프 기동)이다 —
 *    동결 해제 직후와 stop→start 재기동 직후의 거짓 DOWN을 같은 규칙으로 닫는다.
 *
 * **recovery(`FLUSH_RETRY`/`RELOAD_REQUIRED`)는 여기서 판정하지 않는다.**
 * [opensamguk.engine.flush.FlushRecoveryHealthIndicator]가 OPENSAM-132 이래 `!snapshot.ready`에 이미 DOWN을
 * 낸다(같은 컴포짓 헬스라 전체 status는 그대로 DOWN). 중복 판정은 두 인디케이터의 게이트 바인딩 시점이
 * 달라 details가 서로 모순돼 보이는 창만 만든다. 게다가 recovery-gated 상태에서는 루프가 틱을 못 돌리므로
 * 아래 `turn_stalled`가 독립적으로 같은 결론에 도달한다(이중 방어). 다만 그 두 번째 방어는 **즉시가 아니라
 * 3×`tick_seconds` 뒤**(프로덕션 기본 3시간)다 — 즉각 판정은 `FlushRecoveryHealthIndicator` 몫이고,
 * 여기 `turn_stalled`는 그것이 어떤 이유로 못 뜰 때를 위한 시간차 백스톱이다.
 *
 * 비용: [TurnDaemonRunner.diagnostics]는 인메모리 필드만 읽는다(새 DB 읽기 없음). 턴 루프 hot path에는
 * 성공 시각 volatile 쓰기 한 번(이미 존재하던 `lastTickCompletedAt`) 외에 아무것도 더하지 않는다.
 */
@Component
class TurnDaemonHealthIndicator(
    private val pauseGate: DaemonPauseGate,
    private val runner: TurnDaemonRunner,
) : HealthIndicator {

    override fun health(): Health =
        evaluate(pauseGate.isPaused(), runner.diagnostics(), pauseGate.secondsSinceResume())

    companion object {
        /**
         * 지연 허용 배수 — `tick_seconds`의 몇 배까지를 정상으로 볼 것인가.
         *
         * 지표는 **벽시계 기준 마지막 성공 틱 이후 경과**다(게임 클럭 아님 — 캐치업 오탐 방지,
         * `TurnDaemonDiagnostics.lastSuccessfulTickAgeSeconds` 주석 참조). 정상 운영이면 틱은 정확히
         * `tick_seconds`마다, 캐치업이면 그보다 더 자주 성공하므로 이 값은 평시에 `tick_seconds`를 크게
         * 넘지 않는다. 3배는 느린 flush·긴 월간 파이프라인·짧은 동결/해제를 삼킬 여유라 지터로는 울리지
         * 않으면서(오탐 방지), 프로덕션 기본 `tick_seconds=3600`(1시간=1턴) 기준 영구 차단을 3시간 안에
         * 잡는다 — 2.3일 방치와 자릿수가 다르다.
         * 상수 외부화는 하지 않는다(`CLAUDE.md` M-config: post-parity까지 상수 외부화 유예).
         */
        const val STALE_TICK_MULTIPLIER: Long = 3

        fun evaluate(
            paused: Boolean,
            diagnostics: TurnDaemonDiagnostics,
            resumeAgeSeconds: Long? = null,
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
            if (paused) {
                return Health.up()
                    .withDetail("daemon", "paused")
                    // PHP `_119.php:36` 표시 라벨 verbatim.
                    .withDetail("statusLabel", "동결중")
                    .build()
            }
            // 지연 판정의 기준점은 "마지막 성공 틱" 단독이 아니라 **데몬이 틱을 돌 수 있었던 가장 최근
            // 시점** = max(마지막 성공 틱, 마지막 락풀기, 루프 기동)이다. 경과 초로는 셋 중 최솟값.
            //  - 루프 기동: 한 번도 틱을 성공하지 못한 부팅 직후 유예이자, 오래 멈췄다 stop()→start()로
            //    재기동한 경우의 유예다(`lastTickCompletedAt`은 일부러 보존되므로 그것만 보면 멀쩡한 새
            //    루프가 첫 틱 전까지 즉시 turn_stalled다).
            //  - 락풀기: 동결 기간만큼 낡은 마지막 틱 때문에 해제 직후 다음 틱까지(프로덕션 최대 1시간)
            //    거짓 DOWN이 되는 것을 막는다. "위험 배포 전 동결 → 배포 → 해제"가 정상 운영 흐름인데
            //    그 흐름이 배포 게이트를 막으면 안 된다([DaemonPauseGate.secondsSinceResume] 참조 —
            //    사실 기록을 조작하는 대신 판정 기준점만 옮긴 이유도 거기 있다).
            // 완화가 아니다: 세 기준점 중 어느 것이든 임계(3×tick_seconds)를 넘도록 성공 틱이 없으면
            // 여전히 DOWN이다. 재기동/해제가 주는 것은 딱 3틱 주기의 유예뿐이다.
            val neverTicked = diagnostics.lastSuccessfulTickAgeSeconds == null
            val ageSeconds = minOf(
                diagnostics.lastSuccessfulTickAgeSeconds ?: uptimeSeconds,
                uptimeSeconds,
                resumeAgeSeconds ?: Long.MAX_VALUE,
            )
            val allowedSeconds = clock.tickSeconds.toLong() * STALE_TICK_MULTIPLIER
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
                .withDetail("lastTurnTime", clock.lastTurnTime)
                .build()
        }
    }
}
