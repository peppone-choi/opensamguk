package opensamguk.engine.status

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * B1b — 턴 데몬 일시정지(동결) 게이트. PHP `plock`(`type='GAME'`) 락의 단일프로세스 등가물.
 *
 * **PHP 정본(`func.php:1118-1146`).**
 *  - `tryLock()` = `UPDATE plock SET plock=1 WHERE plock=0 AND type='GAME'` → affectedRows>0이면 락 획득(=직전 0).
 *    즉 **compare-and-set 0→1**: 이미 1이면(이미 동결) 실패한다. `_119_b.php:104-111`은 10회 재시도(usleep 0.5s).
 *  - `unlock()` = `UPDATE plock SET plock=0 WHERE type='GAME'` → 무조건 0으로(`_119_b.php:113-115`).
 *  - 표시(`_119.php:36`) = `plock>0 ? "동결중" : "가동중"`.
 *
 * 단일 데몬의 즉시 CAS는 이 인메모리 플래그가 담당하고, durable mirror는 [DurableGameLock]이
 * ChangeRecorder KV delta로 기록한다. [paused]가 true면 [opensamguk.engine.run.TurnDaemonRunner]가
 * scheduled tick을 건너뛴다. one-daemon-write-rule을 지키기 위해 이 클래스는 직접 DB를 쓰지 않는다.
 *
 * 락걸기/락풀기는 데몬 lifecycle 제어이므로 게임엔진에 둔다(어드민 read는 game-api JPA, 여긴 제어 평면).
 */
@Component
class DaemonPauseGate {

    private val log = LoggerFactory.getLogger(DaemonPauseGate::class.java)

    /** plock 등가: true=동결중(틱 정지), false=가동중. */
    private val paused = AtomicBoolean(false)

    /**
     * 마지막 '락풀기'(동결→가동 **전이**) 벽시계 시각. 한 번도 해제된 적 없으면 null.
     *
     * 헬스 지연 판정([TurnDaemonHealthIndicator])의 기준점 후보다. 동결 중에는 틱이 안 도는 것이 정상이라
     * `lastTickCompletedAt`이 동결 기간만큼 낡는데, 해제 직후 그 값만 보면 다음 성공 틱까지(프로덕션
     * `tick_seconds=3600`이면 최대 1시간) `turn_stalled` 거짓 DOWN이다 — "위험 배포 전 동결 → 배포 →
     * 해제"라는 정상 운영 흐름이 배포 게이트를 막는 자충수로 되돌아온다.
     *
     * 대안이었던 "동결 중 `lastTickCompletedAt`을 계속 갱신"은 **채택하지 않았다**. 실제로 안 돈 시간을
     * 돈 것처럼 기록하는 성공 위조이기 때문이다. 사실 기록(`lastTickCompletedAt`)은 그대로 두고
     * **판정 기준점**만 옮긴다.
     */
    @Volatile private var resumedAt: Instant? = null

    /** PHP `plock>0` — 현재 동결 여부. */
    fun isPaused(): Boolean = paused.get()

    /** [resumedAt] 이후 경과 초. 한 번도 해제된 적 없으면 null. */
    fun secondsSinceResume(now: Instant = Instant.now()): Long? =
        resumedAt?.let { Duration.between(it, now).seconds }

    fun restore(locked: Boolean) {
        paused.set(locked)
        // 부팅 시 durable plock 복원은 '전이'가 아니다. 여기서 resumedAt을 심으면 동결 상태로 복원된
        // 프로세스가 해제도 없이 유예를 받는다. 프로세스 기동 직후 구간은 `loopStartedAt` 기준 유예가
        // 이미 덮는다(restore(false)로 가동중 복원되는 경우도 마찬가지).
        resumedAt = null
    }

    /**
     * PHP `tryLock()` = CAS 0→1. 직전이 false(가동중)였을 때만 true를 반환(락 획득). 이미 동결중이면 false.
     * 단일프로세스라 [AtomicBoolean.compareAndSet]이 곧 `UPDATE … WHERE plock=0`의 원자성이다.
     */
    fun tryLock(): Boolean = paused.compareAndSet(false, true)

    /**
     * PHP `_119_b.php:104-111` '락걸기' = `for(i<10){ if(tryLock()) break; usleep(500ms); }`.
     * 단일프로세스에서는 첫 CAS가 성공하면 즉시 true. 이미 동결중이면(직전 락걸기 미해제) 끝까지 false다 —
     * usleep 재시도는 다른 워커가 락을 쥐었다 놓는 PHP 멀티프로세스 상황의 잔재이므로 sleep을 흉내내지 않는다
     * (틱 워커가 별도 스레드이고 락을 보유하지 않으므로 재시도해도 상태가 바뀌지 않는다).
     *
     * @return 락을 새로 획득했으면(=직전 가동중) true, 이미 동결중이면 false.
     */
    fun lock(maxAttempts: Int = 10): Boolean {
        for (attempt in 0 until maxAttempts) {
            if (tryLock()) {
                log.info("DaemonPauseGate locked (락걸기) — attempt={}", attempt + 1)
                return true
            }
        }
        log.info("DaemonPauseGate already locked (이미 동결중) — {} attempts", maxAttempts)
        return false
    }

    /**
     * PHP `unlock()` = 무조건 plock=0. '락풀기'. 직전이 동결중이었으면(=실제로 풀었으면) true.
     * PHP는 affectedRows>0 = 해당 행 존재 시 true이지만, 의미적으로 '실제 변화가 있었는가'를 반환한다.
     */
    fun unlock(): Boolean {
        val wasPaused = paused.getAndSet(false)
        // 실제 전이(동결→가동)일 때만 기록한다. 이미 가동중인데 부른 락풀기는 no-op이므로 유예를
        // 재발급하면 안 된다(있지도 않았던 동결로 지연 판정을 계속 미루게 된다).
        if (wasPaused) resumedAt = Instant.now()
        log.info("DaemonPauseGate unlocked (락풀기) — wasPaused={}", wasPaused)
        return wasPaused
    }
}
