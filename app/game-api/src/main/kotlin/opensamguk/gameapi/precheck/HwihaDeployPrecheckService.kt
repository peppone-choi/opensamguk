package opensamguk.gameapi.precheck

import opensamguk.gameapi.dto.*
import opensamguk.gameapi.read.*
import opensamguk.infra.seed.ResolvedHanWorldArtifacts
import opensamguk.logic.input.*
import opensamguk.logic.retainer.RetainerRules
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class DeployReadForbidden : RuntimeException()

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class HwihaDeployPrecheckService(private val generals: GeneralReadRepository,
    private val retainers: RetainerReadRepository, private val artifacts: ActiveWorldArtifactResolver,
    private val spatial: SpatialStateReadRepository) {
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
        return DeployRules.assess(request, ready.state, ready.bundle.projection.topology,
            ready.selected.world.meta, ready.bundle.landMarchMetrics)
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

    fun options(actorId: Int, ownerUserId: Long): HwihaDeployOptions {
        requireOwner(actorId, ownerUserId)
        val snapshot = snapshot()
        snapshot.failure?.let { return unavailable(it) }
        val ready = requireNotNull(snapshot.ready)
        val topology = ready.bundle.projection.topology
        return try {
            // Missing authority is not an implicit clear map, even before a destination is selected.
            require(LandPassageState.read(ready.selected.world.meta, topology) != null)
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
                HwihaDeployBugok(unit.id, unit.name, unit.troops, failure == null, failure?.let(DeployRules::reason))
            }
            val destinations = ready.selected.cities.sortedBy { it.id }.mapNotNull { city ->
                ready.bundle.projection.bindingsByCityId[city.id]?.landProvinceId?.let {
                    HwihaDeployDestination(it, city.name)
                }
            }.distinctBy { it.provinceId }.sortedBy { it.provinceId }
            val blocked = when {
                ready.state.deployed.any { it.commanderGeneralId == actorId } -> DeploymentFailure.ALREADY_DEPLOYED
                rows.none { it.available } -> DeploymentFailure.UNIT_UNAVAILABLE
                destinations.isEmpty() -> DeploymentFailure.INVALID_DESTINATION
                else -> null
            }
            HwihaDeployOptions(blocked == null, blocked?.name, blocked?.let(DeployRules::reason),
                bugoks = rows, destinations = destinations,
                order = order?.let { HwihaDeployOrder(it.orderId, it.destination.id,
                    if (CorpsEncounter.META_KEY in actor.meta) "ENCOUNTER" else march?.checkpoint?.stop?.name) })
        } catch (_: IllegalArgumentException) { unavailable(DeploymentFailure.STATE_UNAVAILABLE) }
          catch (_: NoSuchElementException) { unavailable(DeploymentFailure.STATE_UNAVAILABLE) }
    }

    private fun unavailable(reason: DeploymentFailure) = HwihaDeployOptions(false, reason.name, DeployRules.reason(reason))
    private data class Ready(val state: DeploymentProjection, val selected: ActiveWorldArtifactSnapshot,
        val bundle: ResolvedHanWorldArtifacts, val people: List<GeneralReadEntity>, val units: List<GeneralBugokReadEntity>)
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
