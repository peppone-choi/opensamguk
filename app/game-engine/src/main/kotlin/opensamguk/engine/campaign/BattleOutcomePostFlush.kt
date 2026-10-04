package opensamguk.engine.campaign

import java.util.Collections

/** A QA export batch whose matching world flush has committed. */
class CommittedBattleOutcomeBatch(
    val worldId: Int,
    val generation: Long,
    observations: List<BattleOutcomeObservation>,
) {
    val observations: List<BattleOutcomeObservation> = Collections.unmodifiableList(
        observations.sortedWith(compareBy(BattleOutcomeObservation::encounterId)))

    init {
        require(worldId > 0 && generation > 0)
        require(this.observations.isNotEmpty() && this.observations.all { it.worldId == worldId })
    }
}

/** Implementations must be idempotent by (worldId, encounterId) if a partial export throws. */
fun interface BattleOutcomeBatchSink {
    fun publish(batch: CommittedBattleOutcomeBatch)
}

/** Single-daemon buffer: resolution observes before commit; only committed batches reach the sink. */
class BattleOutcomePostFlush(private val sink: BattleOutcomeBatchSink) : BattleOutcomeObserver {
    private val uncommitted = mutableListOf<BattleOutcomeObservation>()
    private val committed = ArrayDeque<CommittedBattleOutcomeBatch>()

    internal class UncommittedCheckpoint internal constructor(
        internal val owner: BattleOutcomePostFlush,
        internal val size: Int,
    )

    /** Only the uncommitted suffix belongs to an in-memory turn unit. */
    internal fun checkpointUncommitted(): UncommittedCheckpoint = UncommittedCheckpoint(this, uncommitted.size)

    internal fun restoreUncommitted(checkpoint: UncommittedCheckpoint) {
        require(checkpoint.owner === this && checkpoint.size <= uncommitted.size) { "invalid battle outcome checkpoint" }
        uncommitted.subList(checkpoint.size, uncommitted.size).clear()
    }

    override fun onResolved(observation: BattleOutcomeObservation) {
        uncommitted.add(observation)
    }

    fun afterSuccessfulFlush(worldId: Int, generation: Long) {
        if (uncommitted.isNotEmpty()) {
            val batch = CommittedBattleOutcomeBatch(worldId, generation, uncommitted)
            uncommitted.clear()
            committed.addLast(batch)
        }
        while (committed.isNotEmpty()) {
            sink.publish(committed.first())
            committed.removeFirst()
        }
    }

    /** A stale/ambiguous flush must reload its world; previously committed exports remain retryable. */
    fun quarantineUncommitted() {
        uncommitted.clear()
    }
}
