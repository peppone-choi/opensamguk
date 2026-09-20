package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.retainer.RetainerRules
import opensamguk.logic.world.*

sealed interface DeploymentExecution {
    data class Applied(val corps: HwihaDeployedCorps) : DeploymentExecution
    data class Rejected(val reason: DeploymentFailure) : DeploymentExecution
}

/** Transition boundary only; the personal-turn caller owns command consumption and result recording. */
class HwihaDeploymentExecutor(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot, private val metrics: LandMarchMetricSnapshot) {
    fun assess(request: DeploymentRequest): DeploymentAssessment = projection()?.let {
        HwihaDeploymentRules.assess(request, it)
    } ?: DeploymentAssessment.Rejected(DeploymentFailure.STATE_UNAVAILABLE)

    fun deploy(orderId: String, request: DeploymentRequest): DeploymentExecution {
        if (orderId.isBlank() || orderId.length > 128) return DeploymentExecution.Rejected(DeploymentFailure.INVALID_INPUT)
        val state = projection() ?: return DeploymentExecution.Rejected(DeploymentFailure.STATE_UNAVAILABLE)
        if (state.deployed.any { it.orderId == orderId }) return DeploymentExecution.Rejected(DeploymentFailure.ALREADY_DEPLOYED)
        val assessment = HwihaDeploymentRules.assess(request, state)
        if (assessment is DeploymentAssessment.Rejected) return DeploymentExecution.Rejected(assessment.reason)
        assessment as DeploymentAssessment.Eligible
        val now = world.getState().let { HwihaPhase(it.currentYear, it.currentMonth, it.currentPhase) }
        val corps = HwihaDeployedCorps(orderId, request.ownerId, assessment.commander.id, request.commanderRetainerId,
            assessment.owner.nationId, request.bugokIds.sorted(), now)
        val owner = checkNotNull(world.getGeneralById(request.ownerId))
        val old = HwihaDeploymentState.read(owner.meta)?.corps.orEmpty()
        val next = HwihaDeploymentState((old + corps).sortedBy { it.commanderGeneralId })
        val after = owner.copy(meta = owner.meta + (HwihaDeploymentState.META_KEY to next.toMetaValue()))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(owner), PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
        return DeploymentExecution.Applied(corps)
    }

    /** Corrupt metadata is not an empty battlefield. Consumers must preserve the unavailable result. */
    fun projection(): DeploymentProjection? { return try {
        val positions = world.generalPositionSnapshot() ?: return null
        require(positions.topologyRevision == topology.topologyRevision && positions.topologyHash == topology.contentHash)
        require(positions.knownLandProvinceIds == topology.landProvinceIds &&
            positions.knownWaterZoneIds == topology.waterZones.map { it.id }.toSet())
        val people = world.listGenerals().sortedBy { it.id }
        val corps = people.flatMap { person ->
            HwihaDeploymentState.read(person.meta)?.corps.orEmpty().also { rows ->
                require(rows.all { it.ownerGeneralId == person.id })
            }
        }
        require(corps.map { it.orderId }.distinct().size == corps.size)
        require(corps.map { it.commanderGeneralId }.distinct().size == corps.size)
        require(corps.flatMap { it.bugokIds }.distinct().size == corps.sumOf { it.bugokIds.size })
        DeploymentProjection(world.ruleProfile, people.map { person ->
            val position = positions.stateFor(person.id)
            val march = HwihaMarchState.read(person.meta, topology, metrics)
            require(march == null || march.path.nodeKeys[march.cursor.edgeIndex] == position?.node?.canonicalKey)
            DeploymentPerson(person.id, person.nationId, person.npcState == 2 && (person.userId.isNullOrBlank() || person.userId.toLongOrNull()?.let { it <= 0 } == true),
                position?.node, position?.battlefield != null || march?.stop == LandMarchStop.ENCOUNTER)
        }, world.listBugoks().map { DeploymentUnit(it.id, it.masterGeneralId, it.troops, it.commanderRetainerId) },
            world.listRetainers().map { DeploymentRetainer(it.id, it.masterGeneralId, it.generalId,
                it.relation == RetainerRules.RELATION_LIEUTENANT) }, corps)
    } catch (_: IllegalArgumentException) { null }
    }
}
