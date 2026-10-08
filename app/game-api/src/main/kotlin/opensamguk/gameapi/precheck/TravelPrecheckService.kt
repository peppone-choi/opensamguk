package opensamguk.gameapi.precheck

import com.fasterxml.jackson.databind.ObjectMapper
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
    val code: String? = null, val reason: String? = null,
    val reachability: DestinationReachability = DestinationReachability.UNAVAILABLE,
    val distanceMm: Long? = null, val costMm: Long? = null, val estimatedTurns: Long? = null,
    val arrivesThisTurn: Boolean = false,
    val forcedFatigueDelta: Int? = null, val forcedMoraleDelta: Int? = null,
    val afterFatigue: Int? = null, val afterMorale: Int? = null)
data class TravelWorkplace(val provinceId: String, val name: String, val countyId: Int)
data class TravelOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val destinations: List<TravelDestinationOption> = emptyList(),
    val workplace: TravelWorkplace? = null)

/** A single MVCC snapshot supplies both options and reservation assessment. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class TravelPrecheckService(
    private val generals: GeneralReadRepository,
    private val retainers: RetainerReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val spatial: SpatialStateReadRepository,
    private val gameKv: GameKvReadRepository,
    private val diplomacy: DiplomacyReadRepository,
    private val mapper: ObjectMapper,
) {
    private val provinceNames = ProvinceNamesCache(mapper)

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
        if (CaptiveState.META_KEY in ready.actor.meta)
            return TravelOptions(inputId, false, TravelFailure.STATE_UNAVAILABLE.name,
                TravelRules.CAPTIVE_REASON)
        // Labels use the same selected world and immutable bundle as the route assessment.
        val names = try {
            provinceNames.get(ready.selected.world.id, ready.bundle).dto.names
                .associate { it.provinceId to it.displayName }
        } catch (_: IllegalArgumentException) {
            return TravelOptions(inputId, false, TravelFailure.STATE_UNAVAILABLE.name,
                TravelFailure.STATE_UNAVAILABLE.message)
        } catch (_: IllegalStateException) {
            return TravelOptions(inputId, false, TravelFailure.STATE_UNAVAILABLE.name,
                TravelFailure.STATE_UNAVAILABLE.message)
        } catch (_: java.io.IOException) {
            return TravelOptions(inputId, false, TravelFailure.STATE_UNAVAILABLE.name,
                TravelFailure.STATE_UNAVAILABLE.message)
        }
        val budgetMm = if (inputId == TravelInput.FORCED_MARCH) ForcedMarchTempo.budgetMm
            else LandMarchMetricSnapshot.NORMAL_BUDGET_MM
        if (inputId == TravelInput.RETURN) {
            val request = TravelRequest(actorId, inputId, null)
            val assessment = ready.assess(request)
            val denied = assessment as? TravelAssessment.Rejected
            val destination = (ready.destinationFor(request) as?
                ReturnDestination.Ready)?.node?.id
            val workplace = destination?.let { id -> TravelWorkplace(id, names.getValue(id),
                checkNotNull(CountyAssignment.read(ready.actor.meta)).countyId) }
            val next = (assessment as? TravelAssessment.Eligible)?.path?.nodeKeys?.last()?.removePrefix("land:")
            return TravelOptions(inputId, denied == null, denied?.reason?.name, denied?.reason?.message,
                next?.let { listOf(ready.option(it, assessment, budgetMm, names)) } ?: emptyList(), workplace)
        }
        val ids = ready.bundle.projection.topology.landProvinceIds.sorted()
        val assessments = ready.assessMany(ids.map { id ->
            TravelRequest(actorId, inputId, StrategicNodeRef.LandProvince(id))
        })
        val destinations = ids.mapIndexed { index, id ->
            ready.option(id, assessments[index], budgetMm, names, inputId)
        }
        val available = destinations.any { it.available }
        val failure = if (available) null else destinations.firstOrNull {
            it.code != null && it.code != TravelFailure.ALREADY_THERE.name
        } ?: destinations.firstOrNull { it.code != null }
        return TravelOptions(inputId, available, failure?.code ?: if (available) null else TravelFailure.NO_ROUTE.name,
            failure?.reason ?: if (available) null else TravelFailure.NO_ROUTE.message, destinations)
    }

    private data class Ready(val actor: GeneralReadEntity, val selected: ActiveWorldArtifactSnapshot,
        val bundle: ResolvedWorldArtifacts, val positions: GeneralPositionSnapshot,
        val deployedCommanders: Set<Int>, val passageMeta: Map<String, Any?>,
        val hostileNationIds: Set<Int>) {
        fun destinationFor(request: TravelRequest): ReturnDestination =
            if (request.inputId == TravelInput.RETURN) TravelReturn.resolve(actor.meta, actor.nationId) { cityId ->
                bundle.projection.bindingsByCityId[cityId]?.landProvinceId?.let { StrategicNodeRef.LandProvince(it) }
            } else request.destination?.let(ReturnDestination::Ready)
                ?: ReturnDestination.Rejected(TravelFailure.INVALID_INPUT)

        fun assess(request: TravelRequest): TravelAssessment = assessMany(listOf(request)).single()

        fun assessMany(requests: List<TravelRequest>): List<TravelAssessment> {
            val snapshot = TravelSnapshot(RuleProfile.HWIHA, true, positions.stateFor(actor.id)?.node,
                positions.stateFor(actor.id)?.battlefield != null, actor.id in deployedCommanders,
                hostileNationIds, actor.meta)
            TravelRules.actorFailure(snapshot)?.let { failure ->
                return requests.map { TravelAssessment.Rejected(failure) }
            }
            val destinations = requests.map(::destinationFor)
            val assessments = TravelRules.assessMany(requests.mapIndexed { index, request ->
                request to (destinations[index] as? ReturnDestination.Ready)?.node
            }, snapshot, bundle.projection.topology, bundle.landMarchMetrics, passageMeta)
            return assessments.mapIndexed { index, assessment ->
                (destinations[index] as? ReturnDestination.Rejected)?.let {
                    TravelAssessment.Rejected(it.reason)
                } ?: assessment
            }
        }

        fun option(id: String, assessment: TravelAssessment, budgetMm: Long,
            names: Map<String, String>, inputId: String = TravelInput.RETURN): TravelDestinationOption {
            val name = names.getValue(id)
            val denied = assessment as? TravelAssessment.Rejected
            if (denied != null) return TravelDestinationOption(id, name, false,
                denied.reason.name, denied.reason.message)
            val path = (assessment as TravelAssessment.Eligible).path
            if (inputId == TravelInput.FORCED_MARCH) {
                val preview = requireNotNull(assessment.forcedPreview)
                val reachability = if (preview.estimatedTurns <= 1) DestinationReachability.THIS_TURN
                    else DestinationReachability.MULTI_TURN
                return TravelDestinationOption(id, name, true, reachability = reachability,
                    distanceMm = preview.distanceMm, costMm = path.totalCostMm,
                    estimatedTurns = preview.estimatedTurns, arrivesThisTurn = preview.estimatedTurns <= 1,
                    forcedFatigueDelta = preview.forcedFatigueDelta, forcedMoraleDelta = preview.forcedMoraleDelta,
                    afterFatigue = preview.afterFatigue, afterMorale = preview.afterMorale)
            }
            val estimate = MarchDestinationEstimate.of(path, bundle.landMarchMetrics,
                if (inputId in setOf(TravelInput.MOVE, TravelInput.RETURN)) path.totalCostMm else budgetMm)
            return TravelDestinationOption(id, name, true, reachability = estimate.reachability,
                distanceMm = estimate.distanceMm, costMm = estimate.costMm,
                estimatedTurns = estimate.estimatedTurns, arrivesThisTurn = estimate.arrivesThisTurn)
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
        val passageMeta = GameEnvStateMeta.overlay(
            GameEnvStateMeta.overlay(selected.world.meta, gameKv, mapper, LandPassageState.META_KEY),
            gameKv, mapper, RoadFortState.META_KEY)
        val hostile = diplomacy.findAll().filter { it.stateCode == 0 }.mapNotNull { relation ->
            when (actor.nationId) {
                relation.srcNationId -> relation.destNationId
                relation.destNationId -> relation.srcNationId
                else -> null
            }
        }.toSet()
        Snapshot.Ready(Ready(actor, selected, bundle, positions,
            projection.deployed.mapTo(hashSetOf()) { it.commanderGeneralId }, passageMeta, hostile))
      } catch (_: IllegalArgumentException) { Snapshot.Rejected(TravelFailure.STATE_UNAVAILABLE) }
        catch (_: IllegalStateException) { Snapshot.Rejected(TravelFailure.STATE_UNAVAILABLE) }
        catch (_: java.io.IOException) { Snapshot.Rejected(TravelFailure.STATE_UNAVAILABLE) }
    }
}
