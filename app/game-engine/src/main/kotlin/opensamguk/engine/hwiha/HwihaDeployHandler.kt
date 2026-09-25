package opensamguk.engine.hwiha

import opensamguk.engine.siege.RoadFortPassage

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/** Starts a durable personal order. The single movement stage owns all actual advancement. */
class HwihaDeployHandler(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot?, private val metrics: LandMarchMetricSnapshot?) {
    /**
     * @param npcSelected true only for an input the NPC selector synthesized (no reservation row). It is accepted
     *   only for an unowned actor; its order id is derived from (world, actor, phase) since no request exists.
     */
    fun handle(actorId: Int, argJson: String?, requestId: String?, reservationOwnerUserId: Int?,
        npcSelected: Boolean = false): HwihaTurnOutcome {
        fun reject(reason: DeploymentFailure) = HwihaTurnOutcome.Rejected(HwihaDeployInput.INPUT_ID,reason.name,HwihaDeployRules.reason(reason))
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(DeploymentFailure.WRONG_RULE_PROFILE)
        val npc = npcSelected && reservationOwnerUserId == null && requestId == null &&
            HwihaNpcDeploySelector.isUnowned(world.getGeneralById(actorId)?.userId)
        if (!npc && (reservationOwnerUserId == null || reservationOwnerUserId <= 0 ||
            world.getGeneralById(actorId)?.userId?.toLongOrNull() != reservationOwnerUserId.toLong()))
            return HwihaTurnOutcome.Rejected(HwihaDeployInput.INPUT_ID,"FORBIDDEN","예약한 장수의 소유권이 변경되어 출병할 수 없습니다.")
        val input = HwihaDeployInput.parse(actorId,argJson) ?: return reject(DeploymentFailure.INVALID_INPUT)
        val requestId = if (npc) HwihaNpcDeploySelector.orderId(world, actorId) else requestId
        if (requestId.isNullOrBlank() || requestId.length > 128) return reject(DeploymentFailure.INVALID_INPUT)
        val topology = topology ?: return reject(DeploymentFailure.STATE_UNAVAILABLE)
        val metrics = metrics ?: return reject(DeploymentFailure.STATE_UNAVAILABLE)
        val executor = HwihaDeploymentExecutor(world,recorder,topology,metrics)
        val projection = executor.projection() ?: return reject(DeploymentFailure.STATE_UNAVAILABLE)
        val meta = world.getState().meta
        val passage = try { LandPassageState.read(meta, topology)?.let {
            RoadFortPassage.forNation(world, it, checkNotNull(world.getGeneralById(actorId)).nationId)
        } } catch (_: IllegalArgumentException) { null }
        val assessed = if (passage == null) DeploymentAssessment.Rejected(DeploymentFailure.STATE_UNAVAILABLE) else
            HwihaDeployRules.assess(input,projection,topology,meta,metrics,passage)
        if (assessed is DeploymentAssessment.Rejected) return reject(assessed.reason)
        val order = HwihaCorpsOrder(requestId,actorId,actorId,input.destination,topology.topologyRevision,topology.contentHash)
        return when (val result = executor.deploy(requestId,input.deploymentRequest())) {
            is DeploymentExecution.Rejected -> reject(result.reason)
            is DeploymentExecution.Applied -> {
                order.requireBinding(result.corps,actorId)
                val before = checkNotNull(world.getGeneralById(actorId))
                val after = before.copy(meta=before.meta+(HwihaCorpsOrder.META_KEY to order.toMetaValue()))
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before),PerTurnOverlay.toLogicGeneral(after))
                world.applyGeneralDirtyFree(after)
                HwihaRecords.general(world, actorId, RecordKind.DEPLOY_STARTED, "부대를 거느리고 출병했습니다.",
                    linkedMapOf("orderId" to order.orderId, "destination" to order.destination.canonicalKey,
                        "bugokIds" to result.corps.bugokIds), nationId = after.nationId)
                HwihaTurnOutcome.Applied(HwihaDeployInput.INPUT_ID)
            }
        }
    }
}
