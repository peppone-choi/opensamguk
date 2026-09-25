package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.renown.RenownEventSource
import opensamguk.logic.world.*

/** Reissues each commanded corps' durable destination as the owner's current province. */
class HwihaMusterHandler(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot?, private val metrics: LandMarchMetricSnapshot?,
    private val design: HwihaMilitaryDesign = HwihaMilitaryDesign.CANON) {
    fun handle(actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?,
        npcSelected: Boolean = false): HwihaTurnOutcome {
        fun reject(reason: HwihaMilitaryFailure) = HwihaTurnOutcome.Rejected(HwihaMilitaryInput.MUSTER,
            reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(HwihaMilitaryFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(HwihaMilitaryFailure.ACTOR_NOT_FOUND)
        val npc = npcSelected && ownerUserId == null && HwihaNpcDeploySelector.isUnowned(actor.userId) && actor.npcState >= 2
        if (!npc && (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong()))
            return HwihaTurnOutcome.Rejected(HwihaMilitaryInput.MUSTER, "FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.")
        if (HwihaMilitaryInput.parse(actorId, HwihaMilitaryInput.MUSTER, rawJson) == null)
            return reject(HwihaMilitaryFailure.INVALID_INPUT)
        if (design.status != HwihaMilitaryDesign.CONFIRMED)
            return HwihaTurnOutcome.Rejected(HwihaMilitaryInput.MUSTER,
                InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val turnToken = actor.turnTime.toString()
        val prior = actor.meta[LAST_TURN_KEY] as? Map<*, *>
        if (prior?.get("turn") == turnToken) {
            if (prior["requestId"] == requestId) return HwihaTurnOutcome.Applied(HwihaMilitaryInput.MUSTER,
                (prior["effects"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            return reject(HwihaMilitaryFailure.ALREADY_PROCESSED)
        }
        val topology = topology ?: return reject(HwihaMilitaryFailure.STATE_UNAVAILABLE)
        val metrics = metrics ?: return reject(HwihaMilitaryFailure.STATE_UNAVAILABLE)
        val projection = HwihaDeploymentExecutor(world, recorder, topology, metrics).projection()
            ?: return reject(HwihaMilitaryFailure.STATE_UNAVAILABLE)
        val check = HwihaMusterRules.assess(actorId, projection, topology, metrics, world.getState().meta)
        if (check is HwihaMusterAssessment.Rejected) return reject(check.reason)
        val ready = check as HwihaMusterAssessment.Eligible
        val experience = try { Math.addExact(actor.experience, design.experience) } catch (_: ArithmeticException) {
            return reject(HwihaMilitaryFailure.STATE_UNAVAILABLE)
        }
        val dedication = try { Math.addExact(actor.dedication, design.dedication) } catch (_: ArithmeticException) {
            return reject(HwihaMilitaryFailure.STATE_UNAVAILABLE)
        }
        for (corps in ready.corps) {
            val before = checkNotNull(world.getGeneralById(corps.commanderGeneralId))
            val order = HwihaCorpsOrder(corps.orderId, actorId, corps.commanderGeneralId,
                ready.destination, topology.topologyRevision, topology.contentHash)
            val after = before.copy(meta = before.meta - HwihaCorpsMarchState.META_KEY +
                (HwihaCorpsOrder.META_KEY to order.toMetaValue()))
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
            world.applyGeneralDirtyFree(after)
        }
        val latest = checkNotNull(world.getGeneralById(actorId))
        val effects = listOf("gatheringCorps:${ready.corps.size}", "destination:${ready.destination.canonicalKey}",
            "experience:+${design.experience}", "dedication:+${design.dedication}")
        val stamp = mapOf("turn" to turnToken, "requestId" to requestId,
            "destination" to ready.destination.canonicalKey,
            "commanderIds" to ready.corps.map { it.commanderGeneralId }, "effects" to effects)
        val stamped = latest.copy(experience = experience, dedication = dedication,
            meta = latest.meta + (LAST_TURN_KEY to stamp))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(latest), PerTurnOverlay.toLogicGeneral(stamped))
        world.applyGeneralDirtyFree(stamped)
        HwihaRenownEventRecorder(world, recorder).record(actorId, RenownEventSource.DIRECT_MILITARY_ACTION)
        HwihaRecords.general(world, actorId, HwihaRecordKind.MUSTER_ORDERED,
            "지휘 중인 군단에 현재 省으로 집결하도록 명했습니다.",
            mapOf("destination" to ready.destination.canonicalKey,
                "commanderIds" to ready.corps.map { it.commanderGeneralId }, "requestId" to requestId))
        return HwihaTurnOutcome.Applied(HwihaMilitaryInput.MUSTER, effects)
    }

    companion object { private const val LAST_TURN_KEY = "hwihaMusterLastTurn" }
}
