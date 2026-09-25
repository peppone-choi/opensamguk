package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/** Starts a direct personal march; later empty turns resume its pinned route. */
class HwihaTravelHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot?,
    private val metrics: LandMarchMetricSnapshot?,
    private val reactions: HwihaMarchReactionPolicy = HwihaMarchReactionPolicy.NON_BLOCKING,
    private val outcomes: HwihaWarOutcomeListener = HwihaWarOutcomeListener.NONE,
) {
    fun handle(inputId: String, actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?): HwihaTurnOutcome {
        fun reject(reason: HwihaTravelFailure) = HwihaTurnOutcome.Rejected(inputId, reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(HwihaTravelFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(HwihaTravelFailure.ACTOR_NOT_FOUND)
        if (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong())
            return HwihaTurnOutcome.Rejected(inputId, "FORBIDDEN", "예약한 장수의 소유권이 변경되어 이동할 수 없습니다.")
        val request = HwihaTravelInput.parse(actorId, inputId, rawJson)
            ?: return reject(HwihaTravelFailure.INVALID_INPUT)
        if (requestId.isNullOrBlank() || requestId.length > 128) return reject(HwihaTravelFailure.INVALID_INPUT)
        if ((actor.meta[HwihaPersonalEncounter.REPLAY_KEY] as? Map<*, *>)?.get("orderId") == requestId)
            return HwihaTurnOutcome.Applied(inputId)
        val topology = topology ?: return reject(HwihaTravelFailure.STATE_UNAVAILABLE)
        val metrics = metrics ?: return reject(HwihaTravelFailure.STATE_UNAVAILABLE)
        val destination = if (inputId == HwihaTravelInput.RETURN) {
            when (val resolved = HwihaTravelReturn.resolve(actor.meta, actor.nationId, world::landNodeOfCity)) {
                is HwihaReturnDestination.Ready -> resolved.node
                is HwihaReturnDestination.Rejected -> return reject(resolved.reason)
            }
        } else request.destination ?: return reject(HwihaTravelFailure.INVALID_INPUT)
        val military = HwihaMilitaryPresenceProvider(world, topology, metrics)
        val budget = if (inputId == HwihaTravelInput.FORCED_MARCH) HwihaForcedMarchTempo.budgetMm else LandMarchMetricSnapshot.NORMAL_BUDGET_MM
        val result = HwihaTravelExecutor(world, recorder, topology, metrics).start(requestId, request, destination, budget) { node ->
            HwihaPersonalEncounter.entryAt(world, military, reactions, actorId, node)
        }
        return when (result) {
            is HwihaTravelExecution.Rejected -> reject(result.reason)
            HwihaTravelExecution.NoOrder -> reject(HwihaTravelFailure.STATE_UNAVAILABLE)
            HwihaTravelExecution.AlreadyProcessed -> HwihaTurnOutcome.Applied(inputId)
            is HwihaTravelExecution.Applied -> {
                result.movement.reachedNodes.forEach { reactions.onDirectEntered(world, recorder, actorId, it) }
                record(actorId, result)
                HwihaPersonalEncounter(world, recorder, topology, metrics, reactions, outcomes).settle(actorId, result)
                HwihaTurnOutcome.Applied(inputId)
            }
        }
    }

    companion object {
        internal fun record(world: InMemoryTurnWorld, actorId: Int, result: HwihaTravelExecution.Applied) {
            val text = when (result.state.checkpoint.stop) {
                LandMarchStop.ARRIVED -> "목적 省에 도착했습니다."
                LandMarchStop.BUDGET_EXHAUSTED -> "목적 省으로 이동하고 있습니다."
                LandMarchStop.EDGE_BLOCKED -> "통행로가 닫혀 이동을 멈췄습니다."
                LandMarchStop.ENCOUNTER_UNAVAILABLE -> "진입할 省의 군사·반응 상태를 확인할 수 없어 이동을 멈췄습니다."
                LandMarchStop.ENCOUNTER -> "조우가 발생해 이동을 멈췄습니다."
            }
            HwihaRecords.general(world, actorId, RecordKind.MARCH_DIRECT, text,
                linkedMapOf("inputId" to result.state.inputId, "orderId" to result.state.orderId,
                    "destination" to result.state.destination.canonicalKey, "stop" to result.state.checkpoint.stop.name,
                    "distanceMm" to result.distanceMm,
                    "fatigue" to result.condition?.fatigue,
                    "morale" to result.condition?.morale))
        }
    }

    private fun record(actorId: Int, result: HwihaTravelExecution.Applied) = record(world, actorId, result)
}
