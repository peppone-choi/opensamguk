package opensamguk.engine.flush

/**
 * OPENSAM-130 / ARCH-S3-T1 — immutable delta generation prepare/commit/abort.
 *
 * A prepared generation freezes the active flush batch identity: mutations and new
 * intake/tick work must not append to it. Only [commit] removes the generation after a
 * successful flush; [abort] leaves the batch available for retry (caller keeps deltas).
 *
 * Thread-safety: not concurrent — the turn daemon is single-threaded per world.
 */
class DeltaGenerationSession {

    enum class Phase {
        /** No generation open; mutations allowed. */
        IDLE,

        /** Generation frozen; mutations blocked until commit or abort. */
        PREPARED,
    }

    data class Snapshot(
        val phase: Phase,
        val generation: Long?,
        val lastCommitted: Long?,
        val lastAborted: Long?,
    )

    private var phase: Phase = Phase.IDLE
    private var activeGeneration: Long? = null
    private var nextGeneration: Long = 1L
    private var lastCommitted: Long? = null
    private var lastAborted: Long? = null

    fun snapshot(): Snapshot = Snapshot(phase, activeGeneration, lastCommitted, lastAborted)

    fun phase(): Phase = phase

    fun activeGeneration(): Long? = activeGeneration

    /** True when new recorder mutations / intake / tick work may proceed. */
    fun allowsMutation(): Boolean = phase == Phase.IDLE

    fun allowsIntakeOrTick(): Boolean = phase == Phase.IDLE

    /**
     * Freeze generation N. Blocks further mutations until [commit] or [abort].
     * @return the generation id N
     */
    fun prepare(): Long {
        check(phase == Phase.IDLE) {
            "prepare illegal while phase=$phase active=$activeGeneration"
        }
        val n = nextGeneration++
        activeGeneration = n
        phase = Phase.PREPARED
        return n
    }

    /**
     * Successful flush of generation [generation]. Idempotent if already committed.
     */
    fun commit(generation: Long) {
        if (lastCommitted == generation && phase == Phase.IDLE) {
            return
        }
        when (phase) {
            Phase.PREPARED -> {
                require(activeGeneration == generation) {
                    "commit generation=$generation but active=$activeGeneration"
                }
                lastCommitted = generation
                activeGeneration = null
                phase = Phase.IDLE
            }
            Phase.IDLE -> {
                if (lastCommitted == generation) return
                error("commit generation=$generation illegal in IDLE (lastCommitted=$lastCommitted)")
            }
        }
    }

    /**
     * Failed flush of generation [generation]. Deltas remain with the caller for retry.
     */
    fun abort(generation: Long) {
        if (lastAborted == generation && phase == Phase.IDLE && lastCommitted != generation) {
            return
        }
        when (phase) {
            Phase.PREPARED -> {
                require(activeGeneration == generation) {
                    "abort generation=$generation but active=$activeGeneration"
                }
                lastAborted = generation
                activeGeneration = null
                phase = Phase.IDLE
            }
            Phase.IDLE -> {
                if (lastAborted == generation) return
                error("abort generation=$generation illegal in IDLE (lastAborted=$lastAborted)")
            }
        }
    }

    fun requireMutationAllowed(action: String = "mutation") {
        check(allowsMutation()) {
            "blocked $action during prepared generation=$activeGeneration"
        }
    }
}
