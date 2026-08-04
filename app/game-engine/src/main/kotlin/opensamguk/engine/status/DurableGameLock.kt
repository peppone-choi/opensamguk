package opensamguk.engine.status

import opensamguk.engine.turn.ChangeRecorder

class DurableGameLock(
    private val pauseGate: DaemonPauseGate,
    private val recorder: ChangeRecorder,
    initialPlock: Any?,
) {
    init {
        pauseGate.restore(isLocked(initialPlock))
    }

    fun tryLock(): Boolean {
        if (!pauseGate.tryLock()) return false
        recorder.recordKv("game_env", "game_env", "plock", 1)
        return true
    }

    fun unlock() {
        pauseGate.unlock()
        recorder.recordKv("game_env", "game_env", "plock", 0)
    }

    private fun isLocked(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> value?.toString()?.trim()?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
    }
}
