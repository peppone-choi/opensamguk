package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.*

enum class AssignmentMarchFailure {
    WRONG_RULE_PROFILE, UNKNOWN_ACTOR, INVALID_STATE, INVALID_ASSIGNMENT, POSITION_UNAVAILABLE,
    STALE_PIN, NO_ROUTE, PROGRESS_REJECTED, BATTLE_PENDING,
}
sealed interface AssignmentMarchExecution {
    data class Rejected(val reason: AssignmentMarchFailure) : AssignmentMarchExecution
    data object NoAssignment : AssignmentMarchExecution
    data object AlreadyProcessed : AssignmentMarchExecution
    data class Applied(val state: HwihaMarchState, val movement: LandMarchAdvance.Advanced) : AssignmentMarchExecution
}

/** Recorder-only persistence adapter. Live edge/encounter authorities must be supplied by the caller. */
class HwihaAssignmentMarchExecutor(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val requiredCapacity: Int,
) {
    init { require(requiredCapacity > 0) }

    fun advance(generalId: Int, edges: StrategicEdgeStateSnapshot,
        entryAt: (StrategicNodeRef.LandProvince) -> LandMarchEntry): AssignmentMarchExecution {
        fun reject(reason: AssignmentMarchFailure) = AssignmentMarchExecution.Rejected(reason)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(AssignmentMarchFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(generalId) ?: return reject(AssignmentMarchFailure.UNKNOWN_ACTOR)
        val assignment = try { HwihaCountyAssignment.read(actor.meta) } catch (_: IllegalArgumentException) {
            return reject(AssignmentMarchFailure.INVALID_STATE)
        } ?: return AssignmentMarchExecution.NoAssignment
        if (HwihaDispatchExecutor(world, recorder).assessAssignment(generalId, assignment) !is DispatchAssessment.Eligible)
            return reject(AssignmentMarchFailure.INVALID_ASSIGNMENT)
        val positions = world.generalPositionSnapshot() ?: return reject(AssignmentMarchFailure.POSITION_UNAVAILABLE)
        val position = positions.stateFor(generalId) ?: return reject(AssignmentMarchFailure.POSITION_UNAVAILABLE)
        if (position.battlefield != null) return reject(AssignmentMarchFailure.BATTLE_PENDING)
        if (positions.topologyRevision != topology.topologyRevision || positions.topologyHash != topology.contentHash ||
            positions.knownLandProvinceIds != topology.landProvinceIds ||
            positions.knownWaterZoneIds != topology.waterZones.map { it.id }.toSet()) return reject(AssignmentMarchFailure.STALE_PIN)
        val old = try { HwihaMarchState.read(actor.meta, topology, metrics) } catch (_: IllegalArgumentException) {
            return reject(AssignmentMarchFailure.INVALID_STATE)
        }
        if (old != null && old.path.nodeKeys[old.cursor.edgeIndex] != position.node.canonicalKey)
            return reject(AssignmentMarchFailure.INVALID_STATE)
        val now = world.getState().let { HwihaPhase(it.currentYear, it.currentMonth, it.currentPhase) }
        if (old != null && old.lastAdvancedAt > now) return reject(AssignmentMarchFailure.INVALID_STATE)
        if (old?.lastAdvancedAt == now) return AssignmentMarchExecution.AlreadyProcessed
        // Changing an order must never provide an escape from an unresolved encounter.
        if (old?.stop == LandMarchStop.ENCOUNTER) return reject(AssignmentMarchFailure.BATTLE_PENDING)
        val destination = world.landNodeOfCity(assignment.countyId) ?: return reject(AssignmentMarchFailure.INVALID_ASSIGNMENT)
        val retained = old?.takeIf { it.assignment == assignment }
        val path = retained?.path ?: when (val result = StrategicPathResolver.resolveLandMarch(topology,
            StrategicPathRequest(position.node, destination, requiredCapacity), edges, metrics)) {
            is LandMarchPathResult.Resolved -> result.path
            is LandMarchPathResult.Denied -> return reject(AssignmentMarchFailure.NO_ROUTE)
        }
        if (path.nodeKeys.last() != destination.canonicalKey) return reject(AssignmentMarchFailure.INVALID_STATE)
        val movement = when (val result = LandMarchProgress.advance(topology, metrics, edges, path,
            retained?.cursor ?: LandMarchCursor(path.pathHash), position.node, requiredCapacity, LandMarchMetricSnapshot.NORMAL_BUDGET_MM, entryAt)) {
            is LandMarchAdvance.Advanced -> result
            is LandMarchAdvance.Rejected -> return reject(AssignmentMarchFailure.PROGRESS_REJECTED)
        }
        if (position.revision > Long.MAX_VALUE - movement.reachedNodes.size) return reject(AssignmentMarchFailure.INVALID_STATE)
        val next = HwihaMarchState(assignment, path, movement.cursor, now, movement.stop)
        for (node in movement.reachedNodes) {
            check(recorder.moveGeneral(world, generalId, node) is GeneralPositionChangeResult.Changed) {
                "Validated march position transition was rejected"
            }
        }
        // moveGeneral can change the reference city; retain its latest version when applying metadata.
        val before = checkNotNull(world.getGeneralById(generalId))
        val after = before.copy(meta = before.meta + (HwihaMarchState.META_KEY to next.toMetaValue()))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
        return AssignmentMarchExecution.Applied(next, movement)
    }
}
