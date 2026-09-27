package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/** Returns true when an existing direct order owns this actor's movement stage. */
class TravelTurn(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val reactions: MarchReactionPolicy,
    private val outcomes: WarOutcomeListener = WarOutcomeListener.NONE,
) {
    fun onTurn(actorId: Int): Boolean {
        val actor = world.getGeneralById(actorId) ?: return false
        val state = try { TravelState.read(actor.meta, topology, metrics) }
            catch (_: IllegalArgumentException) {
                clearOrder(actorId)
                Records.general(world, actorId, RecordKind.INPUT_REJECTED,
                    TravelFailure.STATE_UNAVAILABLE.message,
                    mapOf("inputId" to "action.move", "code" to TravelFailure.STATE_UNAVAILABLE.name))
                return false
            } ?: run { recover(actorId); return false }
        val assignment = try { CountyAssignment.read(actor.meta) }
            catch (_: IllegalArgumentException) {
                clearOrder(actorId)
                Records.general(world, actorId, RecordKind.INPUT_REJECTED,
                    TravelFailure.STATE_UNAVAILABLE.message,
                    mapOf("inputId" to state.inputId, "code" to TravelFailure.STATE_UNAVAILABLE.name))
                return false
            }
        if (state.assignmentIdAtStart != assignment?.dispatchId) {
            clearOrder(actorId)
            return false
        }
        if (state.checkpoint.stop == LandMarchStop.ARRIVED) {
            clearOrder(actorId)
            recover(actorId)
            return false
        }
        val military = MilitaryPresenceProvider(world, topology, metrics)
        val budget = if (state.inputId == TravelInput.FORCED_MARCH) ForcedMarchTempo.budgetMm else LandMarchMetricSnapshot.NORMAL_BUDGET_MM
        when (val result = TravelExecutor(world, recorder, topology, metrics).resume(actorId, budget) { node ->
            PersonalEncounter.entryAt(world, military, reactions, actorId, node)
        }) {
            TravelExecution.NoOrder -> return false
            TravelExecution.AlreadyProcessed -> Unit
            is TravelExecution.Rejected -> {
                clearOrder(actorId)
                Records.general(world, actorId, RecordKind.INPUT_REJECTED, result.reason.message,
                    mapOf("inputId" to state.inputId, "code" to result.reason.name))
                return false
            }
            is TravelExecution.Applied -> {
                result.movement.reachedNodes.forEach { reactions.onDirectEntered(world, recorder, actorId, it) }
                TravelHandler.record(world, actorId, result)
                PersonalEncounter(world, recorder, topology, metrics, reactions, outcomes).settle(actorId, result)
            }
        }
        return true
    }

    private fun clearOrder(actorId: Int) {
        val before = world.getGeneralById(actorId) ?: return
        if (TravelState.META_KEY !in before.meta) return
        val after = before.copy(meta = before.meta - TravelState.META_KEY)
        recorder.diffGeneral(opensamguk.engine.turn.PerTurnOverlay.toLogicGeneral(before),
            opensamguk.engine.turn.PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
    }

    private fun recover(actorId: Int) {
        val before = world.getGeneralById(actorId) ?: return
        val now = world.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }
        val condition = try { PersonalTravelCondition.read(before.meta) }
            catch (_: IllegalArgumentException) { return } ?: return
        val last = try { before.meta[RECOVERY_AT]?.let(Phase::read) }
            catch (_: IllegalArgumentException) { return }
        if (last != null && last >= now) return
        val rested = condition.afterRest()
        if (rested == condition) return
        val after = before.copy(meta = before.meta +
            (PersonalTravelCondition.META_KEY to rested.toMetaValue()) + (RECOVERY_AT to now.toMetaValue()))
        recorder.diffGeneral(opensamguk.engine.turn.PerTurnOverlay.toLogicGeneral(before),
            opensamguk.engine.turn.PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
    }

    private companion object { const val RECOVERY_AT = "personalTravelRecoveryAt" }
}
