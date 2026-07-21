package opensamguk.engine.flush

import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.StaleWorldWriterException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.transaction.TransactionSystemException
import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException
import java.util.concurrent.TimeoutException

/**
 * OPENSAM-132 / ARCH-S3-T3 — flush recovery safety gate.
 *
 * After a flush failure the world must not admit new intake/tick until recovery
 * completes. Two blocked modes:
 * - [Mode.FLUSH_RETRY]: known no-commit; same immutable payload may be retried
 * - [Mode.RELOAD_REQUIRED]: stale fence/CAS, ambiguous commit, or restart — no
 *   same-batch retry; operator/engine must reload from primary
 *
 * Readiness is down while not [Mode.READY].
 */
class FlushRecoveryGate {

    enum class Mode {
        READY,
        FLUSH_RETRY,
        RELOAD_REQUIRED,
    }

    data class Snapshot(
        val mode: Mode,
        val worldId: Int?,
        val generation: Long?,
        val reason: String?,
        val hasRetainedPayload: Boolean,
        val ready: Boolean,
    )

    @Volatile
    private var mode: Mode = Mode.READY

    @Volatile
    private var worldId: Int? = null

    @Volatile
    private var generation: Long? = null

    @Volatile
    private var reason: String? = null

    @Volatile
    private var retainedPayload: FlushPayload? = null

    fun mode(): Mode = mode

    fun snapshot(): Snapshot = Snapshot(
        mode = mode,
        worldId = worldId,
        generation = generation,
        reason = reason,
        hasRetainedPayload = retainedPayload != null,
        ready = mode == Mode.READY,
    )

    fun isReady(): Boolean = mode == Mode.READY

    /** New intake/tick/mutation only while healthy. */
    fun allowsIntakeOrTick(): Boolean = mode == Mode.READY

    fun retainedPayload(): FlushPayload? = retainedPayload

    fun enterFlushRetry(
        worldId: Int,
        generation: Long?,
        payload: FlushPayload,
        reason: String,
    ) {
        this.mode = Mode.FLUSH_RETRY
        this.worldId = worldId
        this.generation = generation
        this.reason = reason
        this.retainedPayload = payload
    }

    fun enterReloadRequired(
        worldId: Int,
        generation: Long?,
        reason: String,
    ) {
        this.mode = Mode.RELOAD_REQUIRED
        this.worldId = worldId
        this.generation = generation
        this.reason = reason
        this.retainedPayload = null
    }

    /**
     * Leave recovery after successful retry flush or completed scoped reload.
     */
    fun markRecovered() {
        mode = Mode.READY
        worldId = null
        generation = null
        reason = null
        retainedPayload = null
    }

    fun requireIntakeOrTickAllowed(action: String = "intake/tick") {
        check(allowsIntakeOrTick()) {
            "blocked $action during recovery mode=$mode worldId=$worldId generation=$generation reason=$reason"
        }
    }

    companion object {
        /**
         * Classify a flush failure into [Mode.FLUSH_RETRY] (known rollback / transient)
         * or [Mode.RELOAD_REQUIRED] (stale fence or ambiguous outcome).
         */
        fun classify(error: Throwable): Mode {
            if (error is StaleWorldWriterException) return Mode.RELOAD_REQUIRED
            if (hasCause(error) { it is StaleWorldWriterException }) return Mode.RELOAD_REQUIRED

            // Known retryable: transient SQL / connection / timeout with no commit proof.
            if (error is TransientDataAccessException) return Mode.FLUSH_RETRY
            if (error is DataAccessResourceFailureException) return Mode.FLUSH_RETRY
            if (error is SQLTransientException) return Mode.FLUSH_RETRY
            if (error is SQLRecoverableException) return Mode.FLUSH_RETRY
            if (error is TimeoutException) return Mode.FLUSH_RETRY
            if (hasCause(error) {
                    it is TransientDataAccessException ||
                        it is DataAccessResourceFailureException ||
                        it is SQLTransientException ||
                        it is SQLRecoverableException ||
                        it is TimeoutException
                }
            ) {
                return Mode.FLUSH_RETRY
            }

            // TransactionSystemException often wraps rollback; treat as retry when
            // nested SQL is present and not stale-writer.
            if (error is TransactionSystemException || hasCause(error) { it is TransactionSystemException }) {
                return Mode.FLUSH_RETRY
            }

            // Generic SQLException without transient markers → ambiguous → reload.
            if (error is SQLException || hasCause(error) { it is SQLException }) {
                return Mode.RELOAD_REQUIRED
            }

            // IllegalState from CAS path already handled; default fail-closed.
            return Mode.RELOAD_REQUIRED
        }

        private inline fun hasCause(error: Throwable, pred: (Throwable) -> Boolean): Boolean =
            generateSequence(error) { it.cause }.any(pred)
    }
}
