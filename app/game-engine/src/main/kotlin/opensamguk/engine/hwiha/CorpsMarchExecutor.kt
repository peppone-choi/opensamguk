package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.*

enum class CorpsMarchFailure {
    WRONG_RULE_PROFILE, INVALID_STATE, NO_DEPLOYMENT, INVALID_DEPLOYMENT, POSITION_UNAVAILABLE,
    INVALID_DESTINATION, BATTLE_PENDING, NO_ROUTE, PROGRESS_REJECTED,
}
sealed interface CorpsMarchExecution {
    data class Rejected(val reason: CorpsMarchFailure) : CorpsMarchExecution
    data object AlreadyProcessed : CorpsMarchExecution
    data class Applied(val state: CorpsMarchState, val movement: LandMarchAdvance.Advanced) : CorpsMarchExecution
}

/** One commander's durable movement. Scheduling, entry authorities and command authorization belong to the caller. */
class CorpsMarchExecutor(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val requiredCapacity: Int,
) {
    init { require(requiredCapacity > 0) }

    fun advance(orderId: String, commanderId: Int, destination: StrategicNodeRef.LandProvince,
        edges: StrategicEdgeStateSnapshot,
        entryAt: (StrategicNodeRef.LandProvince) -> LandMarchEntry): CorpsMarchExecution {
        fun reject(reason: CorpsMarchFailure) = CorpsMarchExecution.Rejected(reason)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(CorpsMarchFailure.WRONG_RULE_PROFILE)
        val projection = DeploymentExecutor(world, recorder, topology, metrics).projection()
            ?: return reject(CorpsMarchFailure.INVALID_STATE)
        val corps = projection.deployed.singleOrNull { it.orderId == orderId && it.commanderGeneralId == commanderId }
            ?: return reject(CorpsMarchFailure.NO_DEPLOYMENT)
        val assessment = DeploymentRules.assessActive(corps, projection)
        if (assessment !is DeploymentAssessment.Eligible) return reject(CorpsMarchFailure.INVALID_DEPLOYMENT)
        if (!topology.containsNode(destination)) return reject(CorpsMarchFailure.INVALID_DESTINATION)
        val actor = world.getGeneralById(commanderId) ?: return reject(CorpsMarchFailure.INVALID_STATE)
        val position = world.generalPositionSnapshot()?.stateFor(commanderId)
            ?: return reject(CorpsMarchFailure.POSITION_UNAVAILABLE)
        // projection validated schema, binding and position; read again without discarding corrupt state.
        val old = CorpsMarchState.read(actor.meta, topology, metrics)
        val assignment = MarchState.read(actor.meta, topology, metrics)
        val now = world.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }
        if (corps.startedAt > now || old?.checkpoint?.lastAdvancedAt?.let { it > now } == true ||
            assignment?.lastAdvancedAt?.let { it > now } == true) return reject(CorpsMarchFailure.INVALID_STATE)
        if (old?.checkpoint?.lastAdvancedAt == now || assignment?.lastAdvancedAt == now)
            return CorpsMarchExecution.AlreadyProcessed
        if (assessment.commander.inBattle) return reject(CorpsMarchFailure.BATTLE_PENDING)
        val retained = old?.checkpoint
        if (retained != null && retained.path.nodeKeys.last() != destination.canonicalKey)
            return reject(CorpsMarchFailure.INVALID_DESTINATION)
        val path = retained?.path ?: when (val result = StrategicPathResolver.resolveLandMarch(topology,
            StrategicPathRequest(position.node, destination, requiredCapacity), edges, metrics)) {
            is LandMarchPathResult.Resolved -> result.path
            is LandMarchPathResult.Denied -> return reject(CorpsMarchFailure.NO_ROUTE)
        }
        val movement = when (val result = LandMarchProgress.advance(topology, metrics, edges, path,
            retained?.cursor ?: LandMarchCursor(path.pathHash), position.node, requiredCapacity,
            LandMarchMetricSnapshot.NORMAL_BUDGET_MM, entryAt)) {
            is LandMarchAdvance.Advanced -> result
            is LandMarchAdvance.Rejected -> return reject(CorpsMarchFailure.PROGRESS_REJECTED)
        }
        if (position.revision > Long.MAX_VALUE - movement.reachedNodes.size) return reject(CorpsMarchFailure.INVALID_STATE)
        val next = CorpsMarchState(corps.orderId, corps.ownerGeneralId, commanderId,
            MarchCheckpoint(path, movement.cursor, now, movement.stop))
        next.requireBinding(corps, commanderId)
        for (node in movement.reachedNodes) {
            check(recorder.moveGeneral(world, commanderId, node) is GeneralPositionChangeResult.Changed) {
                "Validated corps march position transition was rejected"
            }
        }
        val before = checkNotNull(world.getGeneralById(commanderId))
        // The deployment takes over movement. The accepted county assignment itself remains intact.
        val after = before.copy(meta = (before.meta - MarchState.META_KEY) +
            (CorpsMarchState.META_KEY to next.toMetaValue()))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
        return CorpsMarchExecution.Applied(next, movement)
    }
}
