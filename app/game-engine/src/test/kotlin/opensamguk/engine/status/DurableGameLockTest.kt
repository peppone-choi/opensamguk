package opensamguk.engine.status

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.KvKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DurableGameLockTest {

    @Test
    fun `new GAME plock records a lock then an unconditional unlock through the recorder`() {
        val recorder = ChangeRecorder()
        val gate = DaemonPauseGate()
        val lock = DurableGameLock(gate, recorder, initialPlock = 0)

        assertTrue(lock.tryLock())
        assertTrue(gate.isPaused())
        assertEquals(1, recorder.kvDirty()[KvKey("game_env", "game_env", "plock")])

        lock.unlock()

        assertFalse(gate.isPaused())
        assertEquals(0, recorder.kvDirty()[KvKey("game_env", "game_env", "plock")])
    }

    @Test
    fun `persisted GAME plock rehydrates the pause gate and preserves PHP CAS semantics`() {
        val recorder = ChangeRecorder()
        val gate = DaemonPauseGate()
        val lock = DurableGameLock(gate, recorder, initialPlock = 1)

        assertTrue(gate.isPaused())
        assertFalse(lock.tryLock(), "persisted plock=1 rejects a second PHP tryLock CAS")

        lock.unlock()

        assertFalse(gate.isPaused())
        assertEquals(0, recorder.kvDirty()[KvKey("game_env", "game_env", "plock")])
    }
}
