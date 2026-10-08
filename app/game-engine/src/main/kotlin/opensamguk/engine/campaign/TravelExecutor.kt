package opensamguk.engine.campaign

import opensamguk.engine.siege.RoadFortPassage
import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.travel.PersonalReturnStop
import opensamguk.logic.world.*

sealed interface TravelExecution {
    data class Rejected(val reason: TravelFailure) : TravelExecution
    data class PolicyHeld(val reason: TravelFailure) : TravelExecution
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
        val target = if (request.inputId == TravelInput.RETURN) {
            val actor = checkNotNull(world.getGeneralById(request.actorId))
            when (val resolved = TravelReturn.resolve(actor.meta, actor.nationId, world::landNodeOfCity)) {
                is ReturnDestination.Ready -> resolved.node
                is ReturnDestination.Rejected -> return reject(resolved.reason)
            }
        } else destination
        val assessment = TravelRules.assess(request, target, current.snapshot, topology, metrics, world.getState().meta)
        if (assessment is TravelAssessment.Rejected) return reject(assessment.reason)
        val path = (assessment as TravelAssessment.Eligible).path
        // MOVE is exactly one approved adjacent edge in one phase, irrespective of terrain cost.
        val singleStep = request.inputId in setOf(TravelInput.MOVE, TravelInput.RETURN)
        val movementBudget = if (singleStep) path.totalCostMm else budgetMm
        val arrival = if (request.inputId == TravelInput.RETURN)
            StrategicNodeRef.LandProvince(path.nodeKeys.last().removePrefix("land:")) else target
        return advance(request.actorId, orderId, request.inputId, arrival, path,
            LandMarchCursor(path.pathHash), null, current.assignmentId, movementBudget, entryAt)
    }

    fun resume(actorId: Int, budgetMm: Long,
        entryAt: (StrategicNodeRef.LandProvince) -> LandMarchEntry): TravelExecution {
        if (budgetMm <= 0) return reject(TravelFailure.INVALID_INPUT)
        val current = state(actorId)
        if (current is ReadState.Failed) return reject(current.reason)
        current as ReadState.Ready
        TravelRules.actorFailure(current.snapshot)?.let { return reject(it) }
        val order = current.order ?: return TravelExecution.NoOrder
        if (order.assignmentIdAtStart != current.assignmentId) return TravelExecution.NoOrder
        if (order.checkpoint.stop == LandMarchStop.ARRIVED) return TravelExecution.NoOrder
        if (order.checkpoint.stop == LandMarchStop.ENCOUNTER) return reject(TravelFailure.BATTLE_PENDING)
        if (current.snapshot.inBattle) return reject(TravelFailure.BATTLE_PENDING)
        if (current.snapshot.commandsCorps) return reject(TravelFailure.CORPS_DEPLOYED)
        return advance(actorId, order.orderId, order.inputId, order.destination, order.checkpoint.path,
            order.checkpoint.cursor, order.checkpoint.lastAdvancedAt, order.assignmentIdAtStart, budgetMm, entryAt,
            resuming = true)
    }

    private fun advance(actorId: Int, orderId: String, inputId: String, destination: StrategicNodeRef.LandProvince,
        path: ResolvedLandMarchPath, cursor: LandMarchCursor, lastAdvancedAt: Phase?,
        assignmentIdAtStart: String?, budgetMm: Long,
        entryAt: (StrategicNodeRef.LandProvince) -> LandMarchEntry, resuming: Boolean = false): TravelExecution {
        val now = world.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }
        if (lastAdvancedAt != null && lastAdvancedAt >= now) return TravelExecution.AlreadyProcessed
        val positions = world.generalPositionSnapshot() ?: return reject(TravelFailure.POSITION_UNAVAILABLE)
        if (positions.topologyRevision != topology.topologyRevision || positions.topologyHash != topology.contentHash ||
            positions.knownLandProvinceIds != topology.landProvinceIds ||
            positions.knownWaterZoneIds != topology.waterZones.map { it.id }.toSet())
            return reject(TravelFailure.STATE_UNAVAILABLE)
        val position = positions.stateFor(actorId) ?: return reject(TravelFailure.POSITION_UNAVAILABLE)
        if (position.battlefield != null) return reject(TravelFailure.BATTLE_PENDING)
        val edges = try { LandPassageState.read(world.getState().meta, topology)?.let {
            RoadFortPassage.forNation(world, it, checkNotNull(world.getGeneralById(actorId)).nationId)
        } }
            catch (_: IllegalArgumentException) { null } ?: return reject(TravelFailure.STATE_UNAVAILABLE)
        val actor = checkNotNull(world.getGeneralById(actorId))
        if (resuming) {
            val hold = try { PersonalTravelPolicyHold.read(actor.meta) }
                catch (_: IllegalArgumentException) { return reject(TravelFailure.STATE_UNAVAILABLE) }
            if (hold?.orderId == orderId) return TravelExecution.PolicyHeld(hold.reason)
            if (inputId == TravelInput.RETURN)
                return TravelExecution.PolicyHeld(TravelFailure.TRAVEL_POLICY_CHANGED)
            if (inputId == TravelInput.MOVE && path.edgeIds.size != 1)
                return TravelExecution.PolicyHeld(TravelFailure.TRAVEL_POLICY_CHANGED)
            if (inputId == TravelInput.FORCED_MARCH) {
                PersonalForcedMarchPolicy.routeFailure(path, metrics)?.let { return TravelExecution.PolicyHeld(it) }
                val old = try { PersonalTravelCondition.read(actor.meta) }
                    catch (_: IllegalArgumentException) { return reject(TravelFailure.STATE_UNAVAILABLE) }
                    ?: PersonalTravelCondition.INITIAL
                val remaining = PersonalTravelDistance.at(path, LandMarchCursor(path.pathHash, path.edgeIds.size), metrics) -
                    PersonalTravelDistance.at(path, cursor, metrics)
                if (!old.canPay(old.forcedCost(remaining)))
                    return TravelExecution.PolicyHeld(TravelFailure.FORCED_MARCH_EXHAUSTED)
            }
        }
        val movementBudget = when {
            inputId in setOf(TravelInput.MOVE, TravelInput.RETURN) && path.edgeIds.size == 1 -> path.totalCostMm
            inputId == TravelInput.FORCED_MARCH -> ForcedMarchTempo.budgetMm
            else -> budgetMm
        }
        val movement = when (val result = LandMarchProgress.advance(topology, metrics, edges, path, cursor,
            position.node, 1, movementBudget, entryAt)) {
            is LandMarchAdvance.Rejected -> return reject(TravelFailure.STATE_UNAVAILABLE)
            is LandMarchAdvance.Advanced -> result
        }
        val next = TravelState(orderId, inputId, destination,
            MarchCheckpoint(path, movement.cursor, now, movement.stop), assignmentIdAtStart)
        val distanceMm = PersonalTravelDistance.at(path, movement.cursor, metrics) -
            PersonalTravelDistance.at(path, cursor, metrics)
        val condition = if (inputId == TravelInput.FORCED_MARCH) {
            val old = try { PersonalTravelCondition.read(actor.meta) }
                catch (_: IllegalArgumentException) { return reject(TravelFailure.STATE_UNAVAILABLE) }
                ?: PersonalTravelCondition.INITIAL
            if (!old.canPay(old.forcedCost(distanceMm))) return reject(TravelFailure.FORCED_MARCH_EXHAUSTED)
            old.afterForcedMarch(cursor, movement.cursor, path, metrics)
        } else null
        for (node in movement.reachedNodes) {
            check(recorder.moveGeneral(world, actorId, node) is GeneralPositionChangeResult.Changed) {
                "Validated direct travel transition was rejected"
            }
        }
        val before = checkNotNull(world.getGeneralById(actorId))
        val cleared = before.meta - MarchState.META_KEY
        val stoppedReturn = if (!resuming && inputId == TravelInput.RETURN) mapOf(
            PersonalReturnStop.META_KEY to PersonalReturnStop(orderId, checkNotNull(assignmentIdAtStart)).toMetaValue(),
            PersonalReturnStop.RECOVERY_AT_KEY to now.toMetaValue()) else emptyMap()
        val baseMeta = if (resuming) cleared else cleared - PersonalTravelPolicyHold.META_KEY - PersonalReturnStop.META_KEY
        val nextMeta = baseMeta + stoppedReturn +
            (TravelState.META_KEY to next.toMetaValue()) +
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
        try { PersonalReturnStop.read(actor.meta) }
            catch (_: IllegalArgumentException) { return ReadState.Failed(TravelFailure.STATE_UNAVAILABLE) }
        val assignment = try { CountyAssignment.read(actor.meta) }
            catch (_: IllegalArgumentException) { return ReadState.Failed(TravelFailure.STATE_UNAVAILABLE) }
        val deployments = DeploymentExecutor(world, recorder, topology, metrics).projection()
            ?: return ReadState.Failed(TravelFailure.STATE_UNAVAILABLE)
        val hostile = world.listDiplomacy().filter { it.state == 0 }.mapNotNull { relation ->
            when (actor.nationId) {
                relation.fromNationId -> relation.toNationId
                relation.toNationId -> relation.fromNationId
                else -> null
            }
        }.toSet()
        return ReadState.Ready(order, assignment?.dispatchId, TravelSnapshot(world.ruleProfile, true, position.node,
            position.battlefield != null, deployments.deployed.any { it.commanderGeneralId == actorId }, hostile,
            actor.meta))
    }

    private fun reject(reason: TravelFailure) = TravelExecution.Rejected(reason)

    private sealed interface ReadState {
        data class Failed(val reason: TravelFailure) : ReadState
        data class Ready(val order: TravelState?, val assignmentId: String?,
            val snapshot: TravelSnapshot) : ReadState
    }
}
