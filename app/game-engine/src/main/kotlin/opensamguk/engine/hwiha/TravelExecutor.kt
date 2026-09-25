package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.*

sealed interface TravelExecution {
    data class Rejected(val reason: TravelFailure) : TravelExecution
    data object NoOrder : TravelExecution
    data object AlreadyProcessed : TravelExecution
    data class Applied(val state: TravelState, val movement: LandMarchAdvance.Advanced,
        val distanceMm: Long, val condition: PersonalTravelCondition?) : TravelExecution
}

/** Advances one personal order through the same pinned land route used by other HWIHA marches. */
class TravelExecutor(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
) {
    fun start(orderId: String, request: TravelRequest, destination: StrategicNodeRef.LandProvince,
        budgetMm: Long, entryAt: (StrategicNodeRef.LandProvince) -> LandMarchEntry): TravelExecution {
        if (orderId.isBlank() || orderId.length > 128 || budgetMm <= 0) return reject(TravelFailure.INVALID_INPUT)
        val current = state(request.actorId)
        if (current is ReadState.Failed) return reject(current.reason)
        current as ReadState.Ready
        if (current.order?.orderId == orderId) return TravelExecution.AlreadyProcessed
        val now = world.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }
        if (current.order != null && current.order.checkpoint.lastAdvancedAt >= now)
            return TravelExecution.AlreadyProcessed
        if (current.order?.checkpoint?.stop == LandMarchStop.ENCOUNTER) return reject(TravelFailure.BATTLE_PENDING)
        val assessment = TravelRules.assess(request, destination, current.snapshot, topology, metrics, world.getState().meta)
        if (assessment is TravelAssessment.Rejected) return reject(assessment.reason)
        val path = (assessment as TravelAssessment.Eligible).path
        return advance(request.actorId, orderId, request.inputId, destination, path,
            LandMarchCursor(path.pathHash), null, current.assignmentId, budgetMm, entryAt)
    }

    fun resume(actorId: Int, budgetMm: Long,
        entryAt: (StrategicNodeRef.LandProvince) -> LandMarchEntry): TravelExecution {
        if (budgetMm <= 0) return reject(TravelFailure.INVALID_INPUT)
        val current = state(actorId)
        if (current is ReadState.Failed) return reject(current.reason)
        current as ReadState.Ready
        val order = current.order ?: return TravelExecution.NoOrder
        if (order.assignmentIdAtStart != current.assignmentId) return TravelExecution.NoOrder
        if (order.checkpoint.stop == LandMarchStop.ARRIVED) return TravelExecution.NoOrder
        if (order.checkpoint.stop == LandMarchStop.ENCOUNTER) return reject(TravelFailure.BATTLE_PENDING)
        if (current.snapshot.inBattle) return reject(TravelFailure.BATTLE_PENDING)
        if (current.snapshot.commandsCorps) return reject(TravelFailure.CORPS_DEPLOYED)
        return advance(actorId, order.orderId, order.inputId, order.destination, order.checkpoint.path,
            order.checkpoint.cursor, order.checkpoint.lastAdvancedAt, order.assignmentIdAtStart, budgetMm, entryAt)
    }

    private fun advance(actorId: Int, orderId: String, inputId: String, destination: StrategicNodeRef.LandProvince,
        path: ResolvedLandMarchPath, cursor: LandMarchCursor, lastAdvancedAt: Phase?,
        assignmentIdAtStart: String?, budgetMm: Long,
        entryAt: (StrategicNodeRef.LandProvince) -> LandMarchEntry): TravelExecution {
        val now = world.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }
        if (lastAdvancedAt != null && lastAdvancedAt >= now) return TravelExecution.AlreadyProcessed
        val positions = world.generalPositionSnapshot() ?: return reject(TravelFailure.POSITION_UNAVAILABLE)
        if (positions.topologyRevision != topology.topologyRevision || positions.topologyHash != topology.contentHash ||
            positions.knownLandProvinceIds != topology.landProvinceIds ||
            positions.knownWaterZoneIds != topology.waterZones.map { it.id }.toSet())
            return reject(TravelFailure.STATE_UNAVAILABLE)
        val position = positions.stateFor(actorId) ?: return reject(TravelFailure.POSITION_UNAVAILABLE)
        if (position.battlefield != null) return reject(TravelFailure.BATTLE_PENDING)
        val edges = try { LandPassageState.read(world.getState().meta, topology) }
            catch (_: IllegalArgumentException) { null } ?: return reject(TravelFailure.STATE_UNAVAILABLE)
        val movement = when (val result = LandMarchProgress.advance(topology, metrics, edges, path, cursor,
            position.node, 1, budgetMm, entryAt)) {
            is LandMarchAdvance.Rejected -> return reject(TravelFailure.STATE_UNAVAILABLE)
            is LandMarchAdvance.Advanced -> result
        }
        val next = TravelState(orderId, inputId, destination,
            MarchCheckpoint(path, movement.cursor, now, movement.stop), assignmentIdAtStart)
        val distanceMm = PersonalTravelDistance.at(path, movement.cursor, metrics) -
            PersonalTravelDistance.at(path, cursor, metrics)
        val condition = if (inputId == TravelInput.FORCED_MARCH) {
            val actor = checkNotNull(world.getGeneralById(actorId))
            val old = try { PersonalTravelCondition.read(actor.meta) }
                catch (_: IllegalArgumentException) { return reject(TravelFailure.STATE_UNAVAILABLE) }
                ?: PersonalTravelCondition.INITIAL
            old.afterForcedMarch(cursor, movement.cursor, path, metrics)
        } else null
        for (node in movement.reachedNodes) {
            check(recorder.moveGeneral(world, actorId, node) is GeneralPositionChangeResult.Changed) {
                "Validated direct travel transition was rejected"
            }
        }
        val before = checkNotNull(world.getGeneralById(actorId))
        val nextMeta = (before.meta - MarchState.META_KEY) + (TravelState.META_KEY to next.toMetaValue()) +
            (condition?.let { mapOf(PersonalTravelCondition.META_KEY to it.toMetaValue()) } ?: emptyMap())
        val after = before.copy(meta = nextMeta)
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
        return TravelExecution.Applied(next, movement, distanceMm, condition)
    }

    private fun state(actorId: Int): ReadState {
        if (world.ruleProfile != RuleProfile.HWIHA) return ReadState.Failed(TravelFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return ReadState.Failed(TravelFailure.ACTOR_NOT_FOUND)
        val position = world.generalPositionSnapshot()?.stateFor(actorId)
            ?: return ReadState.Failed(TravelFailure.POSITION_UNAVAILABLE)
        val order = try { TravelState.read(actor.meta, topology, metrics) }
            catch (_: IllegalArgumentException) { return ReadState.Failed(TravelFailure.STATE_UNAVAILABLE) }
        val assignment = try { CountyAssignment.read(actor.meta) }
            catch (_: IllegalArgumentException) { return ReadState.Failed(TravelFailure.STATE_UNAVAILABLE) }
        val deployments = DeploymentExecutor(world, recorder, topology, metrics).projection()
            ?: return ReadState.Failed(TravelFailure.STATE_UNAVAILABLE)
        return ReadState.Ready(order, assignment?.dispatchId, TravelSnapshot(world.ruleProfile, true, position.node,
            position.battlefield != null, deployments.deployed.any { it.commanderGeneralId == actorId }))
    }

    private fun reject(reason: TravelFailure) = TravelExecution.Rejected(reason)

    private sealed interface ReadState {
        data class Failed(val reason: TravelFailure) : ReadState
        data class Ready(val order: TravelState?, val assignmentId: String?,
            val snapshot: TravelSnapshot) : ReadState
    }
}
