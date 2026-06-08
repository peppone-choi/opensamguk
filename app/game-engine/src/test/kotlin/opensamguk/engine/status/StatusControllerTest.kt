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
}
