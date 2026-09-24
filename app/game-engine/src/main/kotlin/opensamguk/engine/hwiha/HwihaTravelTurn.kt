package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/** Returns true when an existing direct order owns this actor's movement stage. */
class HwihaTravelTurn(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val reactions: HwihaMarchReactionPolicy,
    private val outcomes: HwihaWarOutcomeListener = HwihaWarOutcomeListener.NONE,
) {
    fun onTurn(actorId: Int): Boolean {
        val actor = world.getGeneralById(actorId) ?: return false
        val state = try { HwihaTravelState.read(actor.meta, topology, metrics) }
            catch (_: IllegalArgumentException) {
                HwihaRecords.general(world, actorId, HwihaRecordKind.INPUT_REJECTED,
                    HwihaTravelFailure.STATE_UNAVAILABLE.message,
                    mapOf("inputId" to "action.move", "code" to HwihaTravelFailure.STATE_UNAVAILABLE.name))
                return true
            } ?: return false
        val assignment = try { HwihaCountyAssignment.read(actor.meta) }
            catch (_: IllegalArgumentException) {
                HwihaRecords.general(world, actorId, HwihaRecordKind.INPUT_REJECTED,
                    HwihaTravelFailure.STATE_UNAVAILABLE.message,
                    mapOf("inputId" to state.inputId, "code" to HwihaTravelFailure.STATE_UNAVAILABLE.name))
                return true
            }
        if (state.assignmentIdAtStart != assignment?.dispatchId) return false
        if (state.checkpoint.stop == LandMarchStop.ARRIVED)
            return state.inputId != HwihaTravelInput.RETURN
        val military = HwihaMilitaryPresenceProvider(world, topology, metrics)
        val budget = if (state.inputId == HwihaTravelInput.FORCED_MARCH) 45_000_000L else LandMarchMetricSnapshot.NORMAL_BUDGET_MM
        when (val result = HwihaTravelExecutor(world, recorder, topology, metrics).resume(actorId, budget) { node ->
            HwihaPersonalEncounter.entryAt(world, military, reactions, actorId, node)
        }) {
            HwihaTravelExecution.NoOrder, HwihaTravelExecution.AlreadyProcessed -> Unit
            is HwihaTravelExecution.Rejected -> HwihaRecords.general(world, actorId,
                HwihaRecordKind.INPUT_REJECTED, result.reason.message,
                mapOf("inputId" to state.inputId, "code" to result.reason.name))
            is HwihaTravelExecution.Applied -> {
                result.movement.reachedNodes.forEach { reactions.onDirectEntered(world, recorder, actorId, it) }
                HwihaTravelHandler.record(world, actorId, result)
                HwihaPersonalEncounter(world, recorder, topology, metrics, reactions, outcomes).settle(actorId, result)
            }
        }
        return true
    }
}
