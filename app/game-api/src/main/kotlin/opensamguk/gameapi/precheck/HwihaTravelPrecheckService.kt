package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.*
import opensamguk.infra.seed.ResolvedHanWorldArtifacts
import opensamguk.logic.input.*
import opensamguk.logic.retainer.RetainerRules
import opensamguk.logic.world.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class TravelReadForbidden : RuntimeException()

data class HwihaTravelDestinationOption(val provinceId: String, val name: String, val available: Boolean,
    val code: String? = null, val reason: String? = null)
data class HwihaTravelOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val destinations: List<HwihaTravelDestinationOption> = emptyList())

/** A single MVCC snapshot supplies both options and reservation assessment. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class HwihaTravelPrecheckService(
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

    fun assess(request: HwihaTravelRequest, ownerUserId: Long): HwihaTravelAssessment {
        requireOwner(request.actorId, ownerUserId)
        return when (val result = snapshot(request.actorId)) {
            is Snapshot.Rejected -> HwihaTravelAssessment.Rejected(result.reason)
            is Snapshot.Ready -> result.value.assess(request)
        }
    }

    fun options(actorId: Int, inputId: String, ownerUserId: Long): HwihaTravelOptions {
        requireOwner(actorId, ownerUserId)
        if (inputId !in HwihaTravelInput.INPUT_IDS)
            return HwihaTravelOptions(inputId, false, HwihaTravelFailure.INVALID_INPUT.name,
                HwihaTravelFailure.INVALID_INPUT.message)
        val result = snapshot(actorId)
        if (result is Snapshot.Rejected) return HwihaTravelOptions(inputId, false,
            result.reason.name, result.reason.message)
        val ready = (result as Snapshot.Ready).value
        if (inputId == HwihaTravelInput.RETURN) {
            val assessment = ready.assess(HwihaTravelRequest(actorId, inputId, null))
            val denied = assessment as? HwihaTravelAssessment.Rejected
            val destination = (ready.destinationFor(HwihaTravelRequest(actorId, inputId, null)) as?
                HwihaReturnDestination.Ready)?.node?.id
            return HwihaTravelOptions(inputId, denied == null, denied?.reason?.name, denied?.reason?.message,
                destination?.let { listOf(HwihaTravelDestinationOption(it, ready.nameOfProvince(it), denied == null, denied?.reason?.name,
                    denied?.reason?.message)) } ?: emptyList())
        }
        val destinations = ready.bundle.projection.topology.landProvinceIds.sorted().map { id ->
            val node = StrategicNodeRef.LandProvince(id)
            val assessment = ready.assess(HwihaTravelRequest(actorId, inputId, node))
            val denied = assessment as? HwihaTravelAssessment.Rejected
            HwihaTravelDestinationOption(id, ready.nameOfProvince(id), denied == null, denied?.reason?.name, denied?.reason?.message)
        }
        val available = destinations.any { it.available }
        val firstFailure = destinations.firstOrNull()?.takeUnless { available }?.code
        return HwihaTravelOptions(inputId, available, firstFailure,
            if (available) null else destinations.firstOrNull()?.reason, destinations)
    }

    private data class Ready(val actor: GeneralReadEntity, val selected: ActiveWorldArtifactSnapshot,
        val bundle: ResolvedHanWorldArtifacts, val positions: GeneralPositionSnapshot,
        val deployedCommanders: Set<Int>) {
        private val namesByProvince by lazy {
            buildMap {
                selected.cities.sortedBy { it.id }.forEach { city ->
                    bundle.projection.bindingsByCityId[city.id]?.landProvinceId?.let { putIfAbsent(it, city.name) }
                }
            }
        }
        fun nameOfProvince(id: String): String = namesByProvince[id] ?: id

        fun destinationFor(request: HwihaTravelRequest): HwihaReturnDestination =
            if (request.inputId == HwihaTravelInput.RETURN) HwihaTravelReturn.resolve(actor.meta, actor.nationId) { cityId ->
                bundle.projection.bindingsByCityId[cityId]?.landProvinceId?.let { StrategicNodeRef.LandProvince(it) }
            } else request.destination?.let(HwihaReturnDestination::Ready)
                ?: HwihaReturnDestination.Rejected(HwihaTravelFailure.INVALID_INPUT)

        fun assess(request: HwihaTravelRequest): HwihaTravelAssessment {
            val destination = when (val result = destinationFor(request)) {
                is HwihaReturnDestination.Ready -> result.node
                is HwihaReturnDestination.Rejected -> return HwihaTravelAssessment.Rejected(result.reason)
            }
            return HwihaTravelRules.assess(request, destination, HwihaTravelSnapshot(
                RuleProfile.HWIHA, true, positions.stateFor(actor.id)?.node,
                positions.stateFor(actor.id)?.battlefield != null, actor.id in deployedCommanders),
                bundle.projection.topology, bundle.landMarchMetrics, selected.world.meta)
        }
    }

    private sealed interface Snapshot {
        data class Ready(val value: HwihaTravelPrecheckService.Ready) : Snapshot
        data class Rejected(val reason: HwihaTravelFailure) : Snapshot
    }

    private fun snapshot(actorId: Int): Snapshot {
      return try {
        val selected = artifacts.resolve() ?: return Snapshot.Rejected(HwihaTravelFailure.STATE_UNAVAILABLE)
        if (WorldRuleProfile.require(selected.world.config) != RuleProfile.HWIHA)
            return Snapshot.Rejected(HwihaTravelFailure.WRONG_RULE_PROFILE)
        val bundle = selected.artifacts ?: return Snapshot.Rejected(HwihaTravelFailure.STATE_UNAVAILABLE)
        val people = generals.findAll()
        val cards = retainers.findAll()
        val units = retainers.allBugoks()
        require(people.all { it.worldId == selected.world.id } && cards.all { it.worldId == selected.world.id } &&
            units.all { it.worldId == selected.world.id })
        require(people.map { it.id }.distinct().size == people.size &&
            cards.map { it.id }.distinct().size == cards.size && units.map { it.id }.distinct().size == units.size)
        val actor = people.singleOrNull { it.id == actorId }
            ?: return Snapshot.Rejected(HwihaTravelFailure.ACTOR_NOT_FOUND)
        val positions = spatial.readSnapshot(selected.world.id, bundle.projection.topology).generalPositionSnapshot
        val projection = HwihaDeploymentProjection.build(RuleProfile.HWIHA,
            people.map { DeploymentPersonSource(it.id, it.nationId,
                it.npcState == 2 && (it.userId.isNullOrBlank() || it.userId?.toLongOrNull()?.let { id -> id <= 0 } == true), it.meta) },
            units.map { DeploymentUnit(it.id, it.masterGeneralId, it.troops, it.commanderRetainerId) },
            cards.map { DeploymentRetainer(it.id, it.masterGeneralId, it.generalId, it.relation == RetainerRules.RELATION_LIEUTENANT) },
            positions, bundle.projection.topology, bundle.landMarchMetrics)
            ?: return Snapshot.Rejected(HwihaTravelFailure.STATE_UNAVAILABLE)
        Snapshot.Ready(Ready(actor, selected, bundle, positions,
            projection.deployed.mapTo(hashSetOf()) { it.commanderGeneralId }))
      } catch (_: IllegalArgumentException) { Snapshot.Rejected(HwihaTravelFailure.STATE_UNAVAILABLE) }
        catch (_: IllegalStateException) { Snapshot.Rejected(HwihaTravelFailure.STATE_UNAVAILABLE) }
        catch (_: java.io.IOException) { Snapshot.Rejected(HwihaTravelFailure.STATE_UNAVAILABLE) }
    }
}
