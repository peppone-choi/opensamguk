package opensamguk.engine.status

import opensamguk.engine.run.TurnDaemonRunner
import opensamguk.engine.run.TurnClockSnapshot
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 턴 데몬 상태 + 동결(pause)/해제(resume) 제어 평면.
 *
 * @property profile 게임 프로필(서버 식별).
 * @property state `running`(가동중·틱진행) / `paused`(동결중·plock>0 우선) / `idle`(미동결·루프 미가동).
 * @property running 데몬 루프가 살아 있고 동결되지 않아 실제 틱을 처리 중인지(= `loopAlive && !paused`).
 * @property paused PHP `plock>0` 등가 — 동결 여부.
 * @property loopAlive [TurnDaemonRunner] 루프 **스레드가 실제로 살아 있는지**(`enabled=false`면 false).
 *   `running` 플래그가 아니라 `loopUptimeSeconds != null` — 헬스 인디케이터와 같은 신호라 두 표면이 갈리지 않는다.
 * @property statusLabel 표시 라벨 — PHP `_119.php:36` `plock>0 ? "동결중" : "가동중"` verbatim.
 */
data class TurnDaemonStatus(
    val profile: String,
    val state: String,
    val running: Boolean,
    val paused: Boolean,
    val loopAlive: Boolean,
    val statusLabel: String,
    val serviceMaterialized: Boolean,
    val clock: TurnClockSnapshot?,
    val lastTickStartedAt: String?,
    val lastTickCompletedAt: String?,
    val lastTickFailedAt: String?,
    val lastTickError: String?,
    val successfulTicks: Long,
    val failedTicks: Long,
    val consecutiveFailures: Int,
    /** OPENSAM-132 recovery mode: READY / FLUSH_RETRY / RELOAD_REQUIRED. */
    val recoveryMode: String? = null,
    val recoveryReason: String? = null,
    val recoveryReady: Boolean = true,
    /**
     * OPENSAM-175 진단 표면. `/actuator/health`는 `show-details: never`(기본값)라 본문이
     * `{"status":"UP"}` 뿐이고, 그 설정은 `tools/ops/predeploy_go_check.sh`/`docker-compose.yml`
     * 헬스체크가 물고 있어 이 티켓 범위 밖이다. 따라서 `daemon=turn_stalled`/`clock_unavailable`을
     * 가른 근거 수치는 여기(어드민 status)로 낸다. 기존 필드는 이름·의미 불변
     * (`.github/workflows/deploy.yml`의 `read_daemon_gate_state`가 `paused`/`recoveryReady`를 읽는다).
     */
    val autoStartEnabled: Boolean = true,
    /** 루프 **스레드가 실제 살아 있을 때만** 기동 후 경과 벽시계 초. 죽었으면 null. */
    val loopUptimeSeconds: Long? = null,
    /** 마지막 성공 틱 이후 경과 **벽시계** 초(게임 클럭 아님 — 캐치업 오탐 방지). 성공 틱이 없으면 null. */
    val lastSuccessfulTickAgeSeconds: Long? = null,
    /** 클럭 스냅샷 조회가 실패한 경우의 예외 메시지 — 설정 이상(tickSeconds<=0)과 구분된다. */
    val clockError: String? = null,
)

/** pause/resume 호출 결과 — 호출 후 실제 상태 + 호출이 상태를 바꿨는지(`changed`). */
data class TurnDaemonControlResult(
    val paused: Boolean,
    val changed: Boolean,
    val statusLabel: String,
)

/**
 * B1b — 턴 데몬 락(동결) 제어. PHP `_119.php`/`_119_b.php`의 '락걸기/락풀기' 등가.
 *
 * 락걸기/락풀기는 데몬 lifecycle 제어(쓰기 권한 평면)이므로 게임엔진에 둔다 — one-daemon-write-rule은
 * EntityManager **쓰기** 금지일 뿐, [DaemonPauseGate] 제어 플래그 토글은 그 규칙 밖이다. 어드민 read(국가
 * 통계 등)는 game-api JPA에 둔다(이 컨트롤러 아님).
 *
 * - `GET  /admin/turn-daemon/status` — 실 상태(하드코딩 stub 제거): paused/running/표시라벨.
 * - `POST /admin/turn-daemon/pause`  — 락걸기. `tryLock` 최대 10회([DaemonPauseGate.lock]).
 * - `POST /admin/turn-daemon/resume` — 락풀기. `unlock`([DaemonPauseGate.unlock]).
 */
@RestController
@RequestMapping("/admin/turn-daemon")
class StatusController(
    @Value("\${opensamguk.profile}") private val profile: String,
    private val pauseGate: DaemonPauseGate,
    private val runner: TurnDaemonRunner,
) {

    @GetMapping("/status")
    fun status(): TurnDaemonStatus {
        val paused = pauseGate.isPaused()
        val diagnostics = runner.diagnostics()
        // OPENSAM-175 — `runner.isRunning`(플래그)이 아니라 헬스와 **같은 신호**를 쓴다. `Error`로 루프
        // 스레드가 죽으면 플래그는 true로 남아 어드민만 `loopAlive=true, state="running"`을 보고하고
        // `/actuator/health`는 `loop_not_running` DOWN을 내는 모순이 생긴다.
        val loopAlive = diagnostics.loopUptimeSeconds != null
        val running = loopAlive && !paused
        // 동결(plock>0) 여부가 우선 — PHP `_119.php:36`은 루프 생존과 무관하게 plock만으로 동결중/가동중을
        // 가른다. 따라서 paused면 루프 미가동이라도 state="paused". 미동결·루프 미가동은 "idle"(데몬
        // enabled=false / 부팅 직후 등 — 표시는 statusLabel 가동중이지만 실 틱 미진행).
        val state = when {
            paused -> "paused"
            !loopAlive -> "idle"
            else -> "running"
        }
        return TurnDaemonStatus(
            profile = profile,
            state = state,
            running = running,
            paused = paused,
            loopAlive = loopAlive,
            // PHP `_119.php:36` 표시 라벨 verbatim.
            statusLabel = if (paused) "동결중" else "가동중",
            serviceMaterialized = diagnostics.serviceMaterialized,
            clock = diagnostics.clock,
            lastTickStartedAt = diagnostics.lastTickStartedAt,
            lastTickCompletedAt = diagnostics.lastTickCompletedAt,
            lastTickFailedAt = diagnostics.lastTickFailedAt,
            lastTickError = diagnostics.lastTickError,
            successfulTicks = diagnostics.successfulTicks,
            failedTicks = diagnostics.failedTicks,
            consecutiveFailures = diagnostics.consecutiveFailures,
            recoveryMode = diagnostics.recoveryMode,
            recoveryReason = diagnostics.recoveryReason,
            recoveryReady = diagnostics.recoveryReady,
            autoStartEnabled = diagnostics.autoStartEnabled,
            loopUptimeSeconds = diagnostics.loopUptimeSeconds,
            lastSuccessfulTickAgeSeconds = diagnostics.lastSuccessfulTickAgeSeconds,
            clockError = diagnostics.clockError,
        )
    }

    /** 락걸기 — PHP `_119_b.php:104-112`. `tryLock` 최대 10회. 새로 동결했으면 changed=true. */
    @PostMapping("/pause")
    fun pause(): TurnDaemonControlResult {
        val changed = pauseGate.lock(maxAttempts = 10)
        return TurnDaemonControlResult(paused = true, changed = changed, statusLabel = "동결중")
    }

    /** 락풀기 — PHP `_119_b.php:113-115`. `unlock`. 직전 동결이었으면 changed=true. */
    @PostMapping("/resume")
    fun resume(): TurnDaemonControlResult {
        val changed = pauseGate.unlock()
        return TurnDaemonControlResult(paused = false, changed = changed, statusLabel = "가동중")
    }
}
