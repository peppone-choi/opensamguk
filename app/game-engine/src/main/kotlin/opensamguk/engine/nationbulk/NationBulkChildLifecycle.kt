package opensamguk.engine.nationbulk

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay

enum class NationBulkChildStage {
    PENDING,
    RING_COMMITTED,
    APPLIED,
    NOOP,
    FAILED_AFTER_RING,
    REJECTED_BEFORE_RING,
    ;

    val hasCommittedRing: Boolean
        get() = this != PENDING && this != REJECTED_BEFORE_RING

    val isTerminal: Boolean
        get() = this == APPLIED || this == NOOP || this == REJECTED_BEFORE_RING

    val allowsNextChild: Boolean
        get() = this == APPLIED || this == NOOP
}

data class NationBulkChildLifecycle(
    val childIndex: Int,
    val actorGeneralId: Int,
    val frozenKillturnFloor: Int,
    val stage: NationBulkChildStage = NationBulkChildStage.PENDING,
    val stageVersion: Long = 0,
) {
    init {
        require(childIndex >= 0) { "childIndex must be non-negative" }
        require(stageVersion >= 0) { "stageVersion must be non-negative" }
    }
}

enum class NationBulkLifecycleFailure {
    UNKNOWN_CHILD,
    STALE_STAGE_VERSION,
    INVALID_TRANSITION,
    EARLIER_CHILD_BLOCKS_PROGRESS,
}

sealed interface NationBulkLifecycleResult {
    data class Transitioned(val child: NationBulkChildLifecycle) : NationBulkLifecycleResult

    data class Rejected(
        val failure: NationBulkLifecycleFailure,
        val child: NationBulkChildLifecycle?,
    ) : NationBulkLifecycleResult
}

data class NationBulkActor(
    val generalId: Int,
    val npcState: Int,
    val killturn: Int,
)

data class NationBulkKillturnPatch(
    val generalId: Int,
    val expectedKillturn: Int,
    val nextKillturn: Int,
)

sealed interface NationBulkPatchCommitResult {
    data object Committed : NationBulkPatchCommitResult

    data class Failed(val reason: String) : NationBulkPatchCommitResult
}

interface NationBulkGeneralPort {
    fun actor(generalId: Int): NationBulkActor?

    fun commitKillturn(patch: NationBulkKillturnPatch): NationBulkPatchCommitResult
}

class ChangeRecorderNationBulkGeneralPort(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) : NationBulkGeneralPort {
    override fun actor(generalId: Int): NationBulkActor? {
        val general = world.getGeneralById(generalId) ?: return null
        val killturn = general.meta["killturn"].toIntOrNull() ?: return null
        return NationBulkActor(general.id, general.npcState, killturn)
    }

    override fun commitKillturn(patch: NationBulkKillturnPatch): NationBulkPatchCommitResult {
        val pre = world.getGeneralById(patch.generalId)
            ?: return NationBulkPatchCommitResult.Failed("actor general is unavailable")
        if (pre.npcState >= 2) {
            return NationBulkPatchCommitResult.Failed("actor changed to NPC")
        }
        val currentKillturn = pre.meta["killturn"].toIntOrNull()
            ?: return NationBulkPatchCommitResult.Failed("actor killturn is unavailable")
        if (currentKillturn != patch.expectedKillturn) {
            return NationBulkPatchCommitResult.Failed("actor killturn changed before stage B")
        }
        if (patch.nextKillturn < currentKillturn) {
            return NationBulkPatchCommitResult.Failed("stage B killturn cannot move backward")
        }

        val nextMeta = LinkedHashMap(pre.meta)
        nextMeta["killturn"] = patch.nextKillturn
        val post = pre.copy(meta = nextMeta)

        checkNotNull(recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(pre), PerTurnOverlay.toLogicGeneral(post)))
        checkNotNull(world.applyGeneralDirtyFree(post))
        return NationBulkPatchCommitResult.Committed
    }

    private fun Any?.toIntOrNull(): Int? = when (this) {
        is Number -> toInt()
        is String -> toIntOrNull()
        else -> null
    }
}

class NationBulkLifecycleCoordinator(
    children: List<NationBulkChildLifecycle>,
    private val generalPort: NationBulkGeneralPort,
) {
    private val childOrder = children.map { it.childIndex }
    private val childrenByIndex = LinkedHashMap<Int, NationBulkChildLifecycle>()

    init {
        require(childOrder == childOrder.sorted()) { "children must be supplied in request order" }
        require(childOrder.distinct().size == childOrder.size) { "childIndex values must be unique" }
        children.forEach { childrenByIndex[it.childIndex] = it }
    }

    @Synchronized
    fun child(childIndex: Int): NationBulkChildLifecycle =
        requireNotNull(childrenByIndex[childIndex]) { "unknown child index $childIndex" }

    @Synchronized
    fun children(): List<NationBulkChildLifecycle> = childOrder.map { child(it) }

    @Synchronized
    fun markRingCommitted(childIndex: Int, expectedStageVersion: Long): NationBulkLifecycleResult =
        transition(
            childIndex = childIndex,
            expectedStageVersion = expectedStageVersion,
            allowedStages = setOf(NationBulkChildStage.PENDING),
            nextStage = NationBulkChildStage.RING_COMMITTED,
        )

    @Synchronized
    fun rejectBeforeRing(childIndex: Int, expectedStageVersion: Long): NationBulkLifecycleResult =
        transition(
            childIndex = childIndex,
            expectedStageVersion = expectedStageVersion,
            allowedStages = setOf(NationBulkChildStage.PENDING),
            nextStage = NationBulkChildStage.REJECTED_BEFORE_RING,
        )

    @Synchronized
    fun applyStageB(childIndex: Int, expectedStageVersion: Long): NationBulkLifecycleResult {
        val current = guard(
            childIndex = childIndex,
            expectedStageVersion = expectedStageVersion,
            allowedStages = setOf(NationBulkChildStage.RING_COMMITTED, NationBulkChildStage.FAILED_AFTER_RING),
        ) ?: return rejectedForUnknownOrInvalid(
            childIndex,
            expectedStageVersion,
            setOf(NationBulkChildStage.RING_COMMITTED, NationBulkChildStage.FAILED_AFTER_RING),
        )

        val actor = generalPort.actor(current.actorGeneralId)
        if (actor == null) {
            return replace(current, NationBulkChildStage.FAILED_AFTER_RING)
        }
        if (actor.npcState >= 2 || actor.killturn >= current.frozenKillturnFloor) {
            return replace(current, NationBulkChildStage.NOOP)
        }

        val patch = NationBulkKillturnPatch(
            generalId = current.actorGeneralId,
            expectedKillturn = actor.killturn,
            nextKillturn = maxOf(actor.killturn, current.frozenKillturnFloor),
        )
        return when (generalPort.commitKillturn(patch)) {
            NationBulkPatchCommitResult.Committed -> replace(current, NationBulkChildStage.APPLIED)
            is NationBulkPatchCommitResult.Failed -> replace(current, NationBulkChildStage.FAILED_AFTER_RING)
        }
    }

    private fun transition(
        childIndex: Int,
        expectedStageVersion: Long,
        allowedStages: Set<NationBulkChildStage>,
        nextStage: NationBulkChildStage,
    ): NationBulkLifecycleResult {
        val current = guard(childIndex, expectedStageVersion, allowedStages)
            ?: return rejectedForUnknownOrInvalid(childIndex, expectedStageVersion, allowedStages)
        return replace(current, nextStage)
    }

    private fun guard(
        childIndex: Int,
        expectedStageVersion: Long,
        allowedStages: Set<NationBulkChildStage>,
    ): NationBulkChildLifecycle? {
        val current = childrenByIndex[childIndex] ?: return null
        if (current.stageVersion != expectedStageVersion) return null
        if (childOrder
                .takeWhile { it != childIndex }
                .map { child(it) }
                .any { !it.stage.allowsNextChild }) {
            return null
        }
        if (current.stage !in allowedStages) return null
        return current
    }

    private fun rejectedForUnknownOrInvalid(
        childIndex: Int,
        expectedStageVersion: Long,
        allowedStages: Set<NationBulkChildStage>,
    ): NationBulkLifecycleResult.Rejected {
        val current = childrenByIndex[childIndex]
            ?: return NationBulkLifecycleResult.Rejected(NationBulkLifecycleFailure.UNKNOWN_CHILD, null)
        if (current.stageVersion != expectedStageVersion) {
            return NationBulkLifecycleResult.Rejected(NationBulkLifecycleFailure.STALE_STAGE_VERSION, current)
        }
        if (childOrder
                .takeWhile { it != childIndex }
                .map { child(it) }
                .any { !it.stage.allowsNextChild }) {
            return NationBulkLifecycleResult.Rejected(NationBulkLifecycleFailure.EARLIER_CHILD_BLOCKS_PROGRESS, current)
        }
        if (current.stage !in allowedStages) {
            return NationBulkLifecycleResult.Rejected(NationBulkLifecycleFailure.INVALID_TRANSITION, current)
        }
        error("guard accepted a child that rejectedForUnknownOrInvalid rejected")
    }

    private fun replace(
        current: NationBulkChildLifecycle,
        nextStage: NationBulkChildStage,
    ): NationBulkLifecycleResult.Transitioned {
        val next = current.copy(stage = nextStage, stageVersion = current.stageVersion + 1)
        childrenByIndex[current.childIndex] = next
        return NationBulkLifecycleResult.Transitioned(next)
    }
}
