package opensamguk.gameapi.precheck

import opensamguk.gameapi.dto.*
import opensamguk.gameapi.read.*
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.input.*
import opensamguk.logic.world.*
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.logic.retainer.RetainerRules
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class DeployReadForbidden : RuntimeException()

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class DeployPrecheckService(private val generals: GeneralReadRepository,
    private val retainers: RetainerReadRepository, private val artifacts: ActiveWorldArtifactResolver,
    private val spatial: SpatialStateReadRepository, private val gameKv: GameKvReadRepository,
    private val diplomacy: DiplomacyReadRepository, private val mapper: ObjectMapper) {
    fun requireOwner(actorId: Int, ownerUserId: Long) {
        val actor = generals.findById(actorId).orElse(null)
        if (ownerUserId <= 0 || ownerUserId > Int.MAX_VALUE || actor?.userId?.toLongOrNull() != ownerUserId)
            throw DeployReadForbidden()
    }

    fun assess(request: DeployInput, ownerUserId: Long): DeploymentAssessment {
        requireOwner(request.actorId, ownerUserId)
        val snapshot = snapshot()
        snapshot.failure?.let { return DeploymentAssessment.Rejected(it) }
        val ready = requireNotNull(snapshot.ready)
        val passage = passageFor(ready, request.actorId)
            ?: return DeploymentAssessment.Rejected(DeploymentFailure.STATE_UNAVAILABLE)
        return DeployRules.assess(request, ready.state, ready.bundle.projection.topology,
            ready.selected.world.meta, ready.bundle.landMarchMetrics, passage)
    }

    fun assessMuster(actorId: Int, ownerUserId: Long): MusterAssessment {
        requireOwner(actorId, ownerUserId)
        val snapshot = snapshot()
        snapshot.failure?.let { failure ->
            return MusterAssessment.Rejected(if (failure == DeploymentFailure.WRONG_RULE_PROFILE)
                MilitaryFailure.WRONG_RULE_PROFILE else MilitaryFailure.STATE_UNAVAILABLE)
        }
        val ready = requireNotNull(snapshot.ready)
        return MusterRules.assess(actorId, ready.state, ready.bundle.projection.topology,
            ready.bundle.landMarchMetrics, ready.selected.world.meta)
    }

    fun options(actorId: Int, ownerUserId: Long): DeployOptions {
        requireOwner(actorId, ownerUserId)
        val snapshot = snapshot()
        snapshot.failure?.let { return unavailable(it) }
        val ready = requireNotNull(snapshot.ready)
        val topology = ready.bundle.projection.topology
        return try {
            val passage = requireNotNull(passageFor(ready, actorId))
            require(MarchReactions.presence(ready.selected.world.meta).let {
                it == MarchReactions.Presence.EMPTY || it == MarchReactions.Presence.PENDING })
            require(ready.state.deployed.filter { it.ownerGeneralId == actorId }.all {
                DeploymentRules.assessActive(it, ready.state) is DeploymentAssessment.Eligible
            }) { "Owned deployment relationships are no longer valid" }
            val actor = ready.people.single { it.id == actorId }
            val order = CorpsOrder.read(actor.meta, topology)
            val march = CorpsMarchState.read(actor.meta, topology, ready.bundle.landMarchMetrics)
            val rows = ready.units.filter { it.masterGeneralId == actorId }.sortedBy { it.id }.map { unit ->
                val check = DeploymentRules.assess(DeploymentRequest(actorId, null, listOf(unit.id)), ready.state)
                val failure = (check as? DeploymentAssessment.Rejected)?.reason
                DeployBugok(unit.id, unit.name, unit.troops, failure == null, failure?.let(DeployRules::reason))
            }
            val candidateUnit = rows.firstOrNull { it.available }
            val unitFailure = if (ready.state.deployed.any { it.commanderGeneralId == actorId })
                DeploymentFailure.ALREADY_DEPLOYED else DeploymentFailure.UNIT_UNAVAILABLE
            val candidates = ready.selected.cities.sortedBy { it.id }.mapNotNull { city ->
                ready.bundle.projection.bindingsByCityId[city.id]?.landProvinceId?.let { it to city.name }
            }.distinctBy { it.first }
            val assessments = candidateUnit?.let { unit -> DeployRules.assessRoutes(candidates.map { (province, _) ->
                DeployInput(actorId, listOf(unit.id), StrategicNodeRef.LandProvince(province))
            }, ready.state, topology, ready.selected.world.meta, ready.bundle.landMarchMetrics, passage) }
            val destinations = candidates.mapIndexed { index, (province, name) ->
                val assessment = assessments?.get(index)
                val failure = (assessment?.assessment as? DeploymentAssessment.Rejected)?.reason
                    ?: if (candidateUnit == null) unitFailure else null
                if (failure != null) DeployDestination(province, name, false, failure.name, DeployRules.reason(failure))
                else {
                    val estimate = MarchDestinationEstimate.of(requireNotNull(assessment?.path),
                        ready.bundle.landMarchMetrics, LandMarchMetricSnapshot.NORMAL_BUDGET_MM)
                    DeployDestination(province, name, true, reachability = estimate.reachability,
                        distanceMm = estimate.distanceMm, costMm = estimate.costMm,
                        estimatedTurns = estimate.estimatedTurns, arrivesThisTurn = estimate.arrivesThisTurn)
                }
            }.sortedBy { it.provinceId }
            val blocked = when {
                ready.state.deployed.any { it.commanderGeneralId == actorId } -> DeploymentFailure.ALREADY_DEPLOYED
                rows.none { it.available } -> DeploymentFailure.UNIT_UNAVAILABLE
                destinations.isEmpty() -> DeploymentFailure.INVALID_DESTINATION
                else -> null
            }
            val firstFailure = blocked ?: if (destinations.none { it.available })
                destinations.firstOrNull()?.code?.let { DeploymentFailure.valueOf(it) } ?: DeploymentFailure.NO_ROUTE else null
            DeployOptions(firstFailure == null, firstFailure?.name, firstFailure?.let(DeployRules::reason),
                bugoks = rows, destinations = destinations,
                order = order?.let { DeployOrder(it.orderId, it.destination.id,
                    if (CorpsEncounter.META_KEY in actor.meta) "ENCOUNTER" else march?.checkpoint?.stop?.name) })
        } catch (_: IllegalArgumentException) { unavailable(DeploymentFailure.STATE_UNAVAILABLE) }
          catch (_: NoSuchElementException) { unavailable(DeploymentFailure.STATE_UNAVAILABLE) }
    }

    private fun unavailable(reason: DeploymentFailure) = DeployOptions(false, reason.name, DeployRules.reason(reason))
    private fun passageFor(ready: Ready, actorId: Int): StrategicEdgeStateSnapshot? {
        return try {
            val topology = ready.bundle.projection.topology
            val passageMeta = GameEnvStateMeta.overlay(ready.selected.world.meta, gameKv, mapper,
                LandPassageState.META_KEY)
            val base = LandPassageState.read(passageMeta, topology) ?: return null
            if (ready.bundle.projection.presentation?.roadGates.isNullOrEmpty()) base else {
                val forts = RoadFortState.read(GameEnvStateMeta.overlay(ready.selected.world.meta, gameKv, mapper,
                    RoadFortState.META_KEY))
                val hostile = diplomacy.findAll().filter { it.stateCode == 0 }.mapNotNull { relation ->
                    when (ready.people.single { it.id == actorId }.nationId) {
                        relation.srcNationId -> relation.destNationId
                        relation.destNationId -> relation.srcNationId
                        else -> null
                    }
                }.toSet()
                RoadFortState.forNation(base, forts, hostile)
            }
        } catch (_: RuntimeException) { null }
    }
    private data class Ready(val state: DeploymentProjection, val selected: ActiveWorldArtifactSnapshot,
        val bundle: ResolvedWorldArtifacts, val people: List<GeneralReadEntity>, val units: List<GeneralBugokReadEntity>)
    private data class Snapshot(val ready: Ready? = null, val failure: DeploymentFailure? = null)
    private fun snapshot(): Snapshot = try {
        val selected = requireNotNull(artifacts.resolve())
        val config = selected.world.config
        val profile = opensamguk.logic.input.WorldRuleProfile.require(config)
        if (profile != RuleProfile.HWIHA) Snapshot(failure = DeploymentFailure.WRONG_RULE_PROFILE)
        else {
            val bundle = requireNotNull(selected.artifacts)
            val people = generals.findAll(); val cards = retainers.findAll(); val units = retainers.allBugoks()
            require(people.all { it.worldId == selected.world.id } && cards.all { it.worldId == selected.world.id } &&
                units.all { it.worldId == selected.world.id })
            require(people.map { it.id }.distinct().size == people.size && cards.map { it.id }.distinct().size == cards.size &&
                units.map { it.id }.distinct().size == units.size)
            val state = requireNotNull(DeploymentProjector.build(profile,
                people.map { DeploymentPersonSource(it.id, it.nationId,
                    it.npcState == 2 && (it.userId.isNullOrBlank() || it.userId?.toLongOrNull()?.let { id -> id <= 0 } == true), it.meta) },
                units.map { DeploymentUnit(it.id, it.masterGeneralId, it.troops, it.commanderRetainerId) },
                cards.map { DeploymentRetainer(it.id, it.masterGeneralId, it.generalId, it.relation == RetainerRules.RELATION_LIEUTENANT) },
                spatial.readSnapshot(selected.world.id, bundle.projection.topology).generalPositionSnapshot,
                bundle.projection.topology, bundle.landMarchMetrics))
            Snapshot(ready = Ready(state, selected, bundle, people, units))
        }
    } catch (_: IllegalArgumentException) { Snapshot(failure = DeploymentFailure.STATE_UNAVAILABLE) }
      catch (_: IllegalStateException) { Snapshot(failure = DeploymentFailure.STATE_UNAVAILABLE) }
      catch (_: java.io.IOException) { Snapshot(failure = DeploymentFailure.STATE_UNAVAILABLE) }
}
