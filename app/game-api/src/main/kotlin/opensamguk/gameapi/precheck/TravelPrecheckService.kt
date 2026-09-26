package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.*
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.input.*
import opensamguk.logic.retainer.RetainerRules
import opensamguk.logic.world.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class TravelReadForbidden : RuntimeException()

data class TravelDestinationOption(val provinceId: String, val name: String, val available: Boolean,
    val code: String? = null, val reason: String? = null)
data class TravelOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val destinations: List<TravelDestinationOption> = emptyList())

/** A single MVCC snapshot supplies both options and reservation assessment. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class TravelPrecheckService(
    private val generals: GeneralReadRepository,
    private val retainers: RetainerReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val spatial: SpatialStateReadRepository,
) {
    fun requireOwner(actorId: Int, ownerUserId: Long) {
        val actor = generals.findById(actorId).orElse(null)
        if (ownerUserId <= 0 || ownerUserId > Int.MAX_VALUE || actor?.userId?.toLongOrNull() != ownerUserId)
            throw TravelReadForbidden()
    }

    fun assess(request: TravelRequest, ownerUserId: Long): TravelAssessment {
        requireOwner(request.actorId, ownerUserId)
        return when (val result = snapshot(request.actorId)) {
            is Snapshot.Rejected -> TravelAssessment.Rejected(result.reason)
            is Snapshot.Ready -> result.value.assess(request)
        }
    }

    fun options(actorId: Int, inputId: String, ownerUserId: Long): TravelOptions {
        requireOwner(actorId, ownerUserId)
        if (inputId !in TravelInput.INPUT_IDS)
            return TravelOptions(inputId, false, TravelFailure.INVALID_INPUT.name,
                TravelFailure.INVALID_INPUT.message)
        val result = snapshot(actorId)
        if (result is Snapshot.Rejected) return TravelOptions(inputId, false,
            result.reason.name, result.reason.message)
        val ready = (result as Snapshot.Ready).value
        if (inputId == TravelInput.RETURN) {
            val assessment = ready.assess(TravelRequest(actorId, inputId, null))
            val denied = assessment as? TravelAssessment.Rejected
            val destination = (ready.destinationFor(TravelRequest(actorId, inputId, null)) as?
                ReturnDestination.Ready)?.node?.id
            return TravelOptions(inputId, denied == null, denied?.reason?.name, denied?.reason?.message,
                destination?.let { listOf(TravelDestinationOption(it, ready.nameOfProvince(it), denied == null, denied?.reason?.name,
                    denied?.reason?.message)) } ?: emptyList())
        }
        val (reachable, globalFailure) = ready.reachableDestinations()
        val origin = (ready.positions.stateFor(actorId)?.node as? StrategicNodeRef.LandProvince)?.id
        val destinations = ready.bundle.projection.topology.landProvinceIds.sorted().map { id ->
            val reason = globalFailure ?: when {
                id == origin -> TravelFailure.ALREADY_THERE
                id !in reachable -> TravelFailure.NO_ROUTE
                else -> null
            }
            TravelDestinationOption(id, ready.nameOfProvince(id), reason == null, reason?.name, reason?.message)
        }
        val available = destinations.any { it.available }
        val firstFailure = globalFailure ?: if (available) null else TravelFailure.NO_ROUTE
        return TravelOptions(inputId, available, firstFailure?.name, firstFailure?.message, destinations)
    }

    private data class Ready(val actor: GeneralReadEntity, val selected: ActiveWorldArtifactSnapshot,
        val bundle: ResolvedWorldArtifacts, val positions: GeneralPositionSnapshot,
        val deployedCommanders: Set<Int>) {
        private val namesByProvince by lazy {
            buildMap {
                selected.cities.sortedBy { it.id }.forEach { city ->
                    bundle.projection.bindingsByCityId[city.id]?.landProvinceId?.let { putIfAbsent(it, city.name) }
                }
            }
        }
        fun nameOfProvince(id: String): String = namesByProvince[id] ?: id

        fun destinationFor(request: TravelRequest): ReturnDestination =
            if (request.inputId == TravelInput.RETURN) TravelReturn.resolve(actor.meta, actor.nationId) { cityId ->
                bundle.projection.bindingsByCityId[cityId]?.landProvinceId?.let { StrategicNodeRef.LandProvince(it) }
            } else request.destination?.let(ReturnDestination::Ready)
                ?: ReturnDestination.Rejected(TravelFailure.INVALID_INPUT)

        fun assess(request: TravelRequest): TravelAssessment {
            val destination = when (val result = destinationFor(request)) {
                is ReturnDestination.Ready -> result.node
                is ReturnDestination.Rejected -> return TravelAssessment.Rejected(result.reason)
            }
            return TravelRules.assess(request, destination, TravelSnapshot(
                RuleProfile.HWIHA, true, positions.stateFor(actor.id)?.node,
                positions.stateFor(actor.id)?.battlefield != null, actor.id in deployedCommanders),
                bundle.projection.topology, bundle.landMarchMetrics, selected.world.meta)
        }

        /** One graph traversal answers every destination on this snapshot. Reservation still checks one route exactly. */
        fun reachableDestinations(): Pair<Set<String>, TravelFailure?> {
            val position = positions.stateFor(actor.id) ?: return emptySet<String>() to TravelFailure.POSITION_UNAVAILABLE
            val origin = position.node as? StrategicNodeRef.LandProvince
                ?: return emptySet<String>() to TravelFailure.POSITION_UNAVAILABLE
            if (position.battlefield != null) return emptySet<String>() to TravelFailure.BATTLE_PENDING
            if (actor.id in deployedCommanders) return emptySet<String>() to TravelFailure.CORPS_DEPLOYED
            val topology = bundle.projection.topology
            val metrics = bundle.landMarchMetrics
            if (metrics.topologyRevision != topology.topologyRevision || metrics.topologyHash != topology.contentHash)
                return emptySet<String>() to TravelFailure.STATE_UNAVAILABLE
            return try {
                val passage = LandPassageState.read(selected.world.meta, topology)
                    ?: return emptySet<String>() to TravelFailure.STATE_UNAVAILABLE
                if (MarchReactions.presence(selected.world.meta) in setOf(
                        MarchReactions.Presence.MISSING, MarchReactions.Presence.MALFORMED))
                    return emptySet<String>() to TravelFailure.STATE_UNAVAILABLE
                val nodes = StrategicPathResolver.reachableNodes(topology, setOf(origin), passage, 1,
                    { it is StrategicNodeRef.LandProvince }, LandMarchMetricSnapshot::supports)
                nodes.mapNotNull { (it as? StrategicNodeRef.LandProvince)?.id }.toSet() to null
            } catch (_: IllegalArgumentException) {
                emptySet<String>() to TravelFailure.STATE_UNAVAILABLE
            }
        }
    }

    private sealed interface Snapshot {
        data class Ready(val value: TravelPrecheckService.Ready) : Snapshot
        data class Rejected(val reason: TravelFailure) : Snapshot
    }

    private fun snapshot(actorId: Int): Snapshot {
      return try {
        val selected = artifacts.resolve() ?: return Snapshot.Rejected(TravelFailure.STATE_UNAVAILABLE)
        if (WorldRuleProfile.require(selected.world.config) != RuleProfile.HWIHA)
            return Snapshot.Rejected(TravelFailure.WRONG_RULE_PROFILE)
        val bundle = selected.artifacts ?: return Snapshot.Rejected(TravelFailure.STATE_UNAVAILABLE)
        val people = generals.findAll()
        val cards = retainers.findAll()
        val units = retainers.allBugoks()
        require(people.all { it.worldId == selected.world.id } && cards.all { it.worldId == selected.world.id } &&
            units.all { it.worldId == selected.world.id })
        require(people.map { it.id }.distinct().size == people.size &&
            cards.map { it.id }.distinct().size == cards.size && units.map { it.id }.distinct().size == units.size)
        val actor = people.singleOrNull { it.id == actorId }
            ?: return Snapshot.Rejected(TravelFailure.ACTOR_NOT_FOUND)
        val positions = spatial.readSnapshot(selected.world.id, bundle.projection.topology).generalPositionSnapshot
        val projection = DeploymentProjector.build(RuleProfile.HWIHA,
            people.map { DeploymentPersonSource(it.id, it.nationId,
                it.npcState == 2 && (it.userId.isNullOrBlank() || it.userId?.toLongOrNull()?.let { id -> id <= 0 } == true), it.meta) },
            units.map { DeploymentUnit(it.id, it.masterGeneralId, it.troops, it.commanderRetainerId) },
            cards.map { DeploymentRetainer(it.id, it.masterGeneralId, it.generalId, it.relation == RetainerRules.RELATION_LIEUTENANT) },
            positions, bundle.projection.topology, bundle.landMarchMetrics)
            ?: return Snapshot.Rejected(TravelFailure.STATE_UNAVAILABLE)
        Snapshot.Ready(Ready(actor, selected, bundle, positions,
            projection.deployed.mapTo(hashSetOf()) { it.commanderGeneralId }))
      } catch (_: IllegalArgumentException) { Snapshot.Rejected(TravelFailure.STATE_UNAVAILABLE) }
        catch (_: IllegalStateException) { Snapshot.Rejected(TravelFailure.STATE_UNAVAILABLE) }
        catch (_: java.io.IOException) { Snapshot.Rejected(TravelFailure.STATE_UNAVAILABLE) }
    }
}
