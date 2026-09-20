package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.retainer.RetainerRules
import opensamguk.logic.world.*

sealed interface DeploymentExecution {
    data class Applied(val corps: HwihaDeployedCorps) : DeploymentExecution
    data class Rejected(val reason: DeploymentFailure) : DeploymentExecution
}

/** Transition boundary only; the personal-turn caller owns command consumption and result recording. */
class HwihaDeploymentExecutor(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot, private val metrics: LandMarchMetricSnapshot) {
    fun assess(request: DeploymentRequest): DeploymentAssessment = projection()?.let {
        HwihaDeploymentRules.assess(request, it)
    } ?: DeploymentAssessment.Rejected(DeploymentFailure.STATE_UNAVAILABLE)

    fun deploy(orderId: String, request: DeploymentRequest): DeploymentExecution {
        if (orderId.isBlank() || orderId.length > 128) return DeploymentExecution.Rejected(DeploymentFailure.INVALID_INPUT)
        val state = projection() ?: return DeploymentExecution.Rejected(DeploymentFailure.STATE_UNAVAILABLE)
        if (state.deployed.any { it.orderId == orderId }) return DeploymentExecution.Rejected(DeploymentFailure.ALREADY_DEPLOYED)
        val assessment = HwihaDeploymentRules.assess(request, state)
        if (assessment is DeploymentAssessment.Rejected) return DeploymentExecution.Rejected(assessment.reason)
        assessment as DeploymentAssessment.Eligible
        val now = world.getState().let { HwihaPhase(it.currentYear, it.currentMonth, it.currentPhase) }
        val corps = HwihaDeployedCorps(orderId, request.ownerId, assessment.commander.id, request.commanderRetainerId,
            assessment.owner.nationId, request.bugokIds.sorted(), now)
        val owner = checkNotNull(world.getGeneralById(request.ownerId))
        val old = HwihaDeploymentState.read(owner.meta)?.corps.orEmpty()
        val next = HwihaDeploymentState((old + corps).sortedBy { it.commanderGeneralId })
        val after = owner.copy(meta = owner.meta + (HwihaDeploymentState.META_KEY to next.toMetaValue()))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(owner), PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
        return DeploymentExecution.Applied(corps)
    }

    /** Corrupt metadata remains unavailable in the shared API/engine projection. */
    fun projection(): DeploymentProjection? = HwihaDeploymentProjection.build(
        world.ruleProfile,
        world.listGenerals().map { DeploymentPersonSource(it.id, it.nationId,
            it.npcState == 2 && (it.userId.isNullOrBlank() || it.userId.toLongOrNull()?.let { id -> id <= 0 } == true), it.meta) },
        world.listBugoks().map { DeploymentUnit(it.id, it.masterGeneralId, it.troops, it.commanderRetainerId) },
        world.listRetainers().map { DeploymentRetainer(it.id, it.masterGeneralId, it.generalId,
            it.relation == RetainerRules.RELATION_LIEUTENANT) },
        world.generalPositionSnapshot(), topology, metrics,
    )
}
