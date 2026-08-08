package opensamguk.engine.status

import opensamguk.engine.boot.WorldStateAvailability
import opensamguk.engine.run.TurnDaemonRunner
import opensamguk.engine.run.TurnRunService
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B1b — StatusController가 실 상태(하드코딩 stub 아님)를 반환하고 pause/resume가 그 상태를 토글하는지 검증.
 *
 * 루프 스레드를 띄우지 않고(런너 미start) 게이트 토글 → status 반영만 확인한다. 동결 중 틱 미진행 자체는
 * [opensamguk.engine.run.TurnDaemonRunnerTest]가 별도로 검증한다.
 */
class StatusControllerTest {

    /** 루프를 start하지 않으므로 isRunning=false(=loopAlive false). TurnRunService는 절대 접근되지 않는다. */
    private fun controller(): StatusController {
        val gate = DaemonPauseGate()
        val provider = object : ObjectProvider<TurnRunService> {
            override fun getObject(vararg args: Any?): TurnRunService = error("not used")
            override fun getObject(): TurnRunService = error("not used")
            override fun getIfAvailable(): TurnRunService? = null
            override fun getIfUnique(): TurnRunService? = null
        }
        val runner = TurnDaemonRunner(provider, WorldStateAvailability { true }, gate, daemonEnabled = false, idlePollMs = 10)
        return StatusController(profile = "che", pauseGate = gate, runner = runner)
    }

    @Test
    fun `status is not the hardcoded stub - defaults to not paused`() {
        val status = controller().status()
        assertFalse(status.paused, "초기 동결 아님")
        assertEquals("가동중", status.statusLabel)
        assertEquals("che", status.profile)
        assertFalse(status.serviceMaterialized, "미기동 상태에서는 TurnRunService를 만들지 않는다")
        assertEquals(0, status.successfulTicks)
        assertEquals(0, status.failedTicks)
    }

    @Test
    fun `pause flips status to 동결중 then resume back to 가동중`() {
        val c = controller()

        val paused = c.pause()
        assertTrue(paused.paused)
        assertTrue(paused.changed, "첫 락걸기 — changed")
        assertEquals("동결중", paused.statusLabel)

        val afterPause = c.status()
        assertTrue(afterPause.paused, "status가 실 게이트를 반영")
        assertEquals("동결중", afterPause.statusLabel)
        assertEquals("paused", afterPause.state)
        assertFalse(afterPause.running, "동결 중 running=false")

        val resumed = c.resume()
        assertFalse(resumed.paused)
        assertTrue(resumed.changed, "직전 동결이었으므로 락풀기 changed")
        assertEquals("가동중", resumed.statusLabel)

        val afterResume = c.status()
        assertFalse(afterResume.paused)
        assertEquals("가동중", afterResume.statusLabel)
    }

    @Test
    fun `double pause - second 락걸기 is a no-op (이미 동결중)`() {
        val c = controller()
        assertTrue(c.pause().changed, "첫 락걸기 changed")
        val second = c.pause()
        assertTrue(second.paused, "여전히 동결중")
        assertFalse(second.changed, "이미 동결중이면 changed=false (PHP tryLock CAS 실패)")
    }

    @Test
    fun `resume without prior pause is a no-op`() {
        val c = controller()
        val r = c.resume()
        assertFalse(r.paused)
        assertFalse(r.changed, "동결 아니었으므로 unlock changed=false")
    }

    /**
     * OPENSAM-175 — 어드민 표면과 헬스가 갈리면 안 된다. `Error`로 루프 스레드가 죽으면 `running` 플래그는
     * true로 남지만(stop() 미호출) 어드민은 `loopAlive=false`/`state!="running"`을 보고해야 한다 —
     * 그래야 `/actuator/health`의 `loop_not_running` DOWN과 같은 이야기를 한다.
     *
     * 루프 스레드는 [ObjectProvider.getObject]가 던지는 `Error`로 죽인다(loop()의 `catch (e: Exception)`이
     * 못 잡는다). [opensamguk.engine.run.TurnDaemonRunnerTest]의 같은 회귀와 마찬가지로 uncaught 스택트레이스가
     * test `system-out`에 남는데, Error가 스레드 밖으로 실제 빠져나가는 것이 검증 대상이라 의도된 로그다.
     */
    @Test
    fun `loopAlive is false when the loop thread was killed by an Error`() {
        val gate = DaemonPauseGate()
        val provider = object : ObjectProvider<TurnRunService> {
            override fun getObject(vararg args: Any?): TurnRunService = throw StackOverflowError("simulated")
            override fun getObject(): TurnRunService = throw StackOverflowError("simulated")
            override fun getIfAvailable(): TurnRunService? = null
            override fun getIfUnique(): TurnRunService? = null
        }
        val runner = TurnDaemonRunner(provider, WorldStateAvailability { true }, gate, daemonEnabled = true, idlePollMs = 10)
        val controller = StatusController(profile = "che", pauseGate = gate, runner = runner)
        runner.start()
        try {
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline && runner.diagnostics().loopUptimeSeconds != null) {
                Thread.sleep(10)
            }
            assertTrue(runner.isRunning, "running 플래그는 여전히 true — 이것이 모순의 원천이었다")
            val status = controller.status()
            assertFalse(status.loopAlive, "죽은 루프 스레드는 loopAlive=false")
            assertFalse(status.running, "죽은 루프는 running=false")
            assertEquals("idle", status.state, "죽은 루프는 state=running이 아니다")
        } finally {
            runner.stop()
        }
    }
}
