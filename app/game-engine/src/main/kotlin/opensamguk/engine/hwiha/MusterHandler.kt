package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.renown.RenownEventSource
import opensamguk.logic.world.*

/** Reissues each commanded corps' durable destination as the owner's current province. */
class HwihaMusterHandler(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot?, private val metrics: LandMarchMetricSnapshot?,
    private val design: MilitaryDesign = MilitaryDesign.CANON) {
    fun handle(actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?,
        npcSelected: Boolean = false): HwihaTurnOutcome {
        fun reject(reason: MilitaryFailure) = HwihaTurnOutcome.Rejected(MilitaryInput.MUSTER,
            reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(MilitaryFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(MilitaryFailure.ACTOR_NOT_FOUND)
        val npc = npcSelected && ownerUserId == null && HwihaNpcDeploySelector.isUnowned(actor.userId) && actor.npcState >= 2
        if (!npc && (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong()))
            return HwihaTurnOutcome.Rejected(MilitaryInput.MUSTER, "FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.")
        if (MilitaryInput.parse(actorId, MilitaryInput.MUSTER, rawJson) == null)
            return reject(MilitaryFailure.INVALID_INPUT)
        if (design.status != MilitaryDesign.CONFIRMED)
            return HwihaTurnOutcome.Rejected(MilitaryInput.MUSTER,
                InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val turnToken = actor.turnTime.toString()
        val prior = actor.meta[LAST_TURN_KEY] as? Map<*, *>
        if (prior?.get("turn") == turnToken) {
            if (prior["requestId"] == requestId) return HwihaTurnOutcome.Applied(MilitaryInput.MUSTER,
                (prior["effects"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            return reject(MilitaryFailure.ALREADY_PROCESSED)
        }
        val topology = topology ?: return reject(MilitaryFailure.STATE_UNAVAILABLE)
        val metrics = metrics ?: return reject(MilitaryFailure.STATE_UNAVAILABLE)
        val projection = HwihaDeploymentExecutor(world, recorder, topology, metrics).projection()
            ?: return reject(MilitaryFailure.STATE_UNAVAILABLE)
        val check = MusterRules.assess(actorId, projection, topology, metrics, world.getState().meta)
        if (check is MusterAssessment.Rejected) return reject(check.reason)
        val ready = check as MusterAssessment.Eligible
        val experience = try { Math.addExact(actor.experience, design.experience) } catch (_: ArithmeticException) {
            return reject(MilitaryFailure.STATE_UNAVAILABLE)
        }
        val dedication = try { Math.addExact(actor.dedication, design.dedication) } catch (_: ArithmeticException) {
            return reject(MilitaryFailure.STATE_UNAVAILABLE)
        }
        for (corps in ready.corps) {
            val before = checkNotNull(world.getGeneralById(corps.commanderGeneralId))
            val order = CorpsOrder(corps.orderId, actorId, corps.commanderGeneralId,
                ready.destination, topology.topologyRevision, topology.contentHash)
            val after = before.copy(meta = before.meta - CorpsMarchState.META_KEY +
                (CorpsOrder.META_KEY to order.toMetaValue()))
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
        HwihaRecords.general(world, actorId, RecordKind.MUSTER_ORDERED,
            "지휘 중인 군단에 현재 省으로 집결하도록 명했습니다.",
            mapOf("destination" to ready.destination.canonicalKey,
                "commanderIds" to ready.corps.map { it.commanderGeneralId }, "requestId" to requestId))
        return HwihaTurnOutcome.Applied(MilitaryInput.MUSTER, effects)
    }

    companion object { private const val LAST_TURN_KEY = "hwihaMusterLastTurn" }
}
