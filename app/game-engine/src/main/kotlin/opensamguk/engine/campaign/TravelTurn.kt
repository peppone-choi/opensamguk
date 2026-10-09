package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*
import opensamguk.logic.travel.PersonalReturnStop
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
        returnStop(actorId)?.let { return it }
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
        if (state.assignmentIdAtStart != assignment?.dispatchId ||
            (state.inputId == TravelInput.RETURN && assignment?.nationId != actor.nationId)) {
            clearOrder(actorId)
            return false
        }
        if (state.checkpoint.stop == LandMarchStop.ARRIVED) {
            // A completed return owns idle movement until a new valid order or changed assignment.
            // Otherwise AssignmentMarchTurn would immediately continue marching to the workplace.
            if (state.inputId == TravelInput.RETURN) {
                if (TravelRules.actorFailure(TravelSnapshot(world.ruleProfile, true,
                        world.positionOf(actorId), false, false, emptySet(), actor.meta)) != null) return false
                recover(actorId)
                return true
            }
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
            is TravelExecution.PolicyHeld -> {
                val existing = PersonalTravelPolicyHold.read(actor.meta)
                if (existing?.orderId != state.orderId) {
                    val now = world.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }
                    val after = actor.copy(meta = actor.meta + (PersonalTravelPolicyHold.META_KEY to
                        PersonalTravelPolicyHold(state.orderId, result.reason, now).toMetaValue()))
                    recorder.diffGeneral(opensamguk.engine.turn.PerTurnOverlay.toLogicGeneral(actor),
                        opensamguk.engine.turn.PerTurnOverlay.toLogicGeneral(after))
                    world.applyGeneralDirtyFree(after)
                    Records.general(world, actorId, RecordKind.INPUT_REJECTED, result.reason.message,
                        mapOf("inputId" to state.inputId, "code" to result.reason.name))
                }
                recover(actorId)
            }
            is TravelExecution.Rejected -> {
                // Preserve decoded legacy RETURN checkpoints even when existing battle/source guards reject.
                // Returning ownership also prevents assignment movement from continuing on this idle phase.
                if (state.inputId == TravelInput.RETURN) return true
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

    /** Return intent owns idle movement even when encounter settlement deleted the checkpoint. */
    private fun returnStop(actorId: Int): Boolean? {
        val actor = world.getGeneralById(actorId) ?: return null
        val stop = try { PersonalReturnStop.read(actor.meta) }
            catch (_: IllegalArgumentException) { return true } ?: return null
        val assignment = try { CountyAssignment.read(actor.meta) }
            catch (_: IllegalArgumentException) { return true }
        if (stop.assignmentId != assignment?.dispatchId || assignment?.nationId != actor.nationId) {
            val after = actor.copy(meta = actor.meta - PersonalReturnStop.META_KEY)
            recorder.diffGeneral(opensamguk.engine.turn.PerTurnOverlay.toLogicGeneral(actor),
                opensamguk.engine.turn.PerTurnOverlay.toLogicGeneral(after))
            world.applyGeneralDirtyFree(after)
            return null
        }
        // Corps owns its earlier movement stage. Captivity never grants automatic personal movement.
        if (CaptiveState.META_KEY in actor.meta) return true
        recover(actorId)
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

    private companion object { const val RECOVERY_AT = PersonalReturnStop.RECOVERY_AT_KEY }
}
