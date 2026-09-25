package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.*

sealed interface HwihaTravelExecution {
    data class Rejected(val reason: HwihaTravelFailure) : HwihaTravelExecution
    data object NoOrder : HwihaTravelExecution
    data object AlreadyProcessed : HwihaTravelExecution
    data class Applied(val state: HwihaTravelState, val movement: LandMarchAdvance.Advanced,
        val distanceMm: Long, val condition: HwihaPersonalTravelCondition?) : HwihaTravelExecution
}

/** Advances one personal order through the same pinned land route used by other HWIHA marches. */
class HwihaTravelExecutor(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
) {
    fun start(orderId: String, request: HwihaTravelRequest, destination: StrategicNodeRef.LandProvince,
        budgetMm: Long, entryAt: (StrategicNodeRef.LandProvince) -> LandMarchEntry): HwihaTravelExecution {
        if (orderId.isBlank() || orderId.length > 128 || budgetMm <= 0) return reject(HwihaTravelFailure.INVALID_INPUT)
        val current = state(request.actorId)
        if (current is ReadState.Failed) return reject(current.reason)
        current as ReadState.Ready
        if (current.order?.orderId == orderId) return HwihaTravelExecution.AlreadyProcessed
        val now = world.getState().let { HwihaPhase(it.currentYear, it.currentMonth, it.currentPhase) }
        if (current.order != null && current.order.checkpoint.lastAdvancedAt >= now)
            return HwihaTravelExecution.AlreadyProcessed
        if (current.order?.checkpoint?.stop == LandMarchStop.ENCOUNTER) return reject(HwihaTravelFailure.BATTLE_PENDING)
        val assessment = HwihaTravelRules.assess(request, destination, current.snapshot, topology, metrics, world.getState().meta)
        if (assessment is HwihaTravelAssessment.Rejected) return reject(assessment.reason)
        val path = (assessment as HwihaTravelAssessment.Eligible).path
        return advance(request.actorId, orderId, request.inputId, destination, path,
            LandMarchCursor(path.pathHash), null, current.assignmentId, budgetMm, entryAt)
    }

    fun resume(actorId: Int, budgetMm: Long,
        entryAt: (StrategicNodeRef.LandProvince) -> LandMarchEntry): HwihaTravelExecution {
        if (budgetMm <= 0) return reject(HwihaTravelFailure.INVALID_INPUT)
        val current = state(actorId)
        if (current is ReadState.Failed) return reject(current.reason)
        current as ReadState.Ready
        val order = current.order ?: return HwihaTravelExecution.NoOrder
        if (order.assignmentIdAtStart != current.assignmentId) return HwihaTravelExecution.NoOrder
        if (order.checkpoint.stop == LandMarchStop.ARRIVED) return HwihaTravelExecution.NoOrder
        if (order.checkpoint.stop == LandMarchStop.ENCOUNTER) return reject(HwihaTravelFailure.BATTLE_PENDING)
        if (current.snapshot.inBattle) return reject(HwihaTravelFailure.BATTLE_PENDING)
        if (current.snapshot.commandsCorps) return reject(HwihaTravelFailure.CORPS_DEPLOYED)
        return advance(actorId, order.orderId, order.inputId, order.destination, order.checkpoint.path,
            order.checkpoint.cursor, order.checkpoint.lastAdvancedAt, order.assignmentIdAtStart, budgetMm, entryAt)
    }

    private fun advance(actorId: Int, orderId: String, inputId: String, destination: StrategicNodeRef.LandProvince,
        path: ResolvedLandMarchPath, cursor: LandMarchCursor, lastAdvancedAt: HwihaPhase?,
        assignmentIdAtStart: String?, budgetMm: Long,
        entryAt: (StrategicNodeRef.LandProvince) -> LandMarchEntry): HwihaTravelExecution {
        val now = world.getState().let { HwihaPhase(it.currentYear, it.currentMonth, it.currentPhase) }
        if (lastAdvancedAt != null && lastAdvancedAt >= now) return HwihaTravelExecution.AlreadyProcessed
        val positions = world.generalPositionSnapshot() ?: return reject(HwihaTravelFailure.POSITION_UNAVAILABLE)
        if (positions.topologyRevision != topology.topologyRevision || positions.topologyHash != topology.contentHash ||
            positions.knownLandProvinceIds != topology.landProvinceIds ||
            positions.knownWaterZoneIds != topology.waterZones.map { it.id }.toSet())
            return reject(HwihaTravelFailure.STATE_UNAVAILABLE)
        val position = positions.stateFor(actorId) ?: return reject(HwihaTravelFailure.POSITION_UNAVAILABLE)
        if (position.battlefield != null) return reject(HwihaTravelFailure.BATTLE_PENDING)
        val edges = try { LandPassageState.read(world.getState().meta, topology) }
            catch (_: IllegalArgumentException) { null } ?: return reject(HwihaTravelFailure.STATE_UNAVAILABLE)
        val movement = when (val result = LandMarchProgress.advance(topology, metrics, edges, path, cursor,
            position.node, 1, budgetMm, entryAt)) {
            is LandMarchAdvance.Rejected -> return reject(HwihaTravelFailure.STATE_UNAVAILABLE)
            is LandMarchAdvance.Advanced -> result
        }
        val next = HwihaTravelState(orderId, inputId, destination,
            HwihaMarchCheckpoint(path, movement.cursor, now, movement.stop), assignmentIdAtStart)
        val distanceMm = HwihaPersonalTravelDistance.at(path, movement.cursor, metrics) -
            HwihaPersonalTravelDistance.at(path, cursor, metrics)
        val condition = if (inputId == HwihaTravelInput.FORCED_MARCH) {
            val actor = checkNotNull(world.getGeneralById(actorId))
            val old = try { HwihaPersonalTravelCondition.read(actor.meta) }
                catch (_: IllegalArgumentException) { return reject(HwihaTravelFailure.STATE_UNAVAILABLE) }
                ?: HwihaPersonalTravelCondition.INITIAL
            old.afterForcedMarch(cursor, movement.cursor, path, metrics)
        } else null
        for (node in movement.reachedNodes) {
            check(recorder.moveGeneral(world, actorId, node) is GeneralPositionChangeResult.Changed) {
                "Validated direct travel transition was rejected"
            }
        }
        val before = checkNotNull(world.getGeneralById(actorId))
        val nextMeta = (before.meta - HwihaMarchState.META_KEY) + (HwihaTravelState.META_KEY to next.toMetaValue()) +
            (condition?.let { mapOf(HwihaPersonalTravelCondition.META_KEY to it.toMetaValue()) } ?: emptyMap())
        val after = before.copy(meta = nextMeta)
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
        return HwihaTravelExecution.Applied(next, movement, distanceMm, condition)
    }

    private fun state(actorId: Int): ReadState {
        if (world.ruleProfile != RuleProfile.HWIHA) return ReadState.Failed(HwihaTravelFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return ReadState.Failed(HwihaTravelFailure.ACTOR_NOT_FOUND)
        val position = world.generalPositionSnapshot()?.stateFor(actorId)
            ?: return ReadState.Failed(HwihaTravelFailure.POSITION_UNAVAILABLE)
        val order = try { HwihaTravelState.read(actor.meta, topology, metrics) }
            catch (_: IllegalArgumentException) { return ReadState.Failed(HwihaTravelFailure.STATE_UNAVAILABLE) }
        val assignment = try { HwihaCountyAssignment.read(actor.meta) }
            catch (_: IllegalArgumentException) { return ReadState.Failed(HwihaTravelFailure.STATE_UNAVAILABLE) }
        val deployments = HwihaDeploymentExecutor(world, recorder, topology, metrics).projection()
            ?: return ReadState.Failed(HwihaTravelFailure.STATE_UNAVAILABLE)
        return ReadState.Ready(order, assignment?.dispatchId, HwihaTravelSnapshot(world.ruleProfile, true, position.node,
            position.battlefield != null, deployments.deployed.any { it.commanderGeneralId == actorId }))
    }

    private fun reject(reason: HwihaTravelFailure) = HwihaTravelExecution.Rejected(reason)

    private sealed interface ReadState {
        data class Failed(val reason: HwihaTravelFailure) : ReadState
        data class Ready(val order: HwihaTravelState?, val assignmentId: String?,
            val snapshot: HwihaTravelSnapshot) : ReadState
    }
}
