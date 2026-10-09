package opensamguk.engine.flush

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/** Discard every singleton and buffer before primary bootstrap; at most one attempt per process. */
class PrimaryDaemonRecovery(
    private val closeFailedContext: () -> Unit,
    private val prepareFreshContext: () -> Unit,
    private val attempted: AtomicBoolean,
    private val launch: (() -> Unit) -> Unit = { work -> Thread(work, "primary-daemon-recovery").start() },
) {
    fun requestRestart(): Boolean {
        if (!attempted.compareAndSet(false, true)) return false
        launch {
            try {
                closeFailedContext()
                prepareFreshContext()
            } catch (error: Exception) {
                LoggerFactory.getLogger(javaClass).error("Primary daemon reinitialization failed; no further automatic retry", error)
            }
        }
        return true
    }

    companion object {
        val processAttempt = AtomicBoolean(false)
    }
}
