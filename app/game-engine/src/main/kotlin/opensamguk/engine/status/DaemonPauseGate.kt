package opensamguk.engine.status

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
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
 * opensamguk은 게임당 단일 게임엔진 데몬이므로 plock 행 = 이 인메모리 플래그(AtomicBoolean) 하나로 충분하다.
 * [paused]가 true면 [opensamguk.engine.run.TurnDaemonRunner] 루프가 틱을 건너뛴다(드레인·flush 없음 →
 * InMemoryTurnWorld 동결). one-daemon-write-rule 무위반: 쓰기 경로(EntityManager/JDBC)와 무관한 제어 플래그.
 *
 * 락걸기/락풀기는 데몬 lifecycle 제어이므로 게임엔진에 둔다(어드민 read는 game-api JPA, 여긴 제어 평면).
 */
@Component
class DaemonPauseGate {

    private val log = LoggerFactory.getLogger(DaemonPauseGate::class.java)

    /** plock 등가: true=동결중(틱 정지), false=가동중. */
    private val paused = AtomicBoolean(false)

    /** PHP `plock>0` — 현재 동결 여부. */
    fun isPaused(): Boolean = paused.get()

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
        log.info("DaemonPauseGate unlocked (락풀기) — wasPaused={}", wasPaused)
        return wasPaused
    }
}
