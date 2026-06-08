package opensamguk.engine.status

import opensamguk.engine.run.TurnDaemonRunner
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
 * @property loopAlive [TurnDaemonRunner] SmartLifecycle 루프 스레드가 가동 중인지(`enabled=false`면 false).
 * @property statusLabel 표시 라벨 — PHP `_119.php:36` `plock>0 ? "동결중" : "가동중"` verbatim.
 */
data class TurnDaemonStatus(
    val profile: String,
    val state: String,
    val running: Boolean,
    val paused: Boolean,
    val loopAlive: Boolean,
    val statusLabel: String,
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
        val loopAlive = runner.isRunning
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
