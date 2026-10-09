package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.DomesticReader
import opensamguk.gameapi.read.ProvinceNamesCacheReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class PeopleTargetOption(val generalId: Int, val name: String, val available: Boolean,
    val code: String? = null, val reason: String? = null)
data class PeopleOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val undiscoveredCount: Int? = null, val targets: List<PeopleTargetOption> = emptyList())
data class CaptiveTargetRead(val generalId: Int, val name: String, val nationId: Int, val nationName: String?,
    val heldProvinceId: String, val actualProvinceId: String?, val capturedAt: Phase,
    val expiry: String = "NONE", val persuadeAvailable: Boolean, val persuadeCode: String? = null,
    val persuadeReason: String? = null, val releaseAvailable: Boolean, val releaseCode: String? = null,
    val releaseReason: String? = null, val heldProvinceName: String? = null)
data class CaptivesRead(val available: Boolean, val code: String? = null,
    val reason: String? = null, val targets: List<CaptiveTargetRead> = emptyList())

/** Only discovered free people and the actor's own captives are named to the caller. */
@Service
class PeopleOptionsService(private val reader: DomesticReader,
    private val provinceNames: ProvinceNamesCacheReader,
    private val catalog: InputCatalog = InputCatalog.load(),
    private val design: PeopleDesign = PeopleDesign.CANON) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun captives(actorId: Int, userId: Long): CaptivesRead {
        reader.requireOwner(actorId, userId)
        val state = reader.snapshot().state ?: return CaptivesRead(false,
            PeopleFailure.STATE_UNAVAILABLE.name, PeopleFailure.STATE_UNAVAILABLE.message)
        if (state.person(actorId) == null) return CaptivesRead(false,
            PeopleFailure.ACTOR_NOT_FOUND.name, PeopleFailure.ACTOR_NOT_FOUND.message)
        val namesByProvince by lazy {
            try {
                provinceNames.current()?.dto?.names.orEmpty().associate { it.provinceId to it.displayName }
            } catch (_: IllegalArgumentException) { emptyMap() }
              catch (_: IllegalStateException) { emptyMap() }
              catch (_: java.io.IOException) { emptyMap() }
        }
        val targets = state.people.mapNotNull { target ->
            val marker = runCatching { CaptiveState.read(target.meta) }.getOrNull()
                ?.takeIf { it.captorGeneralId == actorId } ?: return@mapNotNull null
            val persuasion = PeopleRules.assess(PeopleRequest(actorId, PeopleInput.PERSUADE_CAPTIVE, target.id), state)
            val persuadeFailure = (persuasion as? PeopleAssessment.Rejected)?.reason
            val releaseFailure = CaptiveReleaseRules.assess(CaptiveReleaseRequest(actorId, target.id), state)
            CaptiveTargetRead(target.id, target.name, target.nationId, state.nation(target.nationId)?.name,
                marker.heldProvinceId, target.node,
                marker.capturedAt, persuadeAvailable = persuadeFailure == null,
                persuadeCode = persuadeFailure?.name, persuadeReason = persuadeFailure?.message,
                releaseAvailable = releaseFailure == null,
                releaseCode = releaseFailure?.name, releaseReason = releaseFailure?.message,
                heldProvinceName = namesByProvince[marker.heldProvinceId]?.takeIf { it.isNotBlank() })
        }.sortedBy { it.generalId }
        return CaptivesRead(true, targets = targets)
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(inputId: String, actorId: Int, userId: Long): PeopleOptions {
        reader.requireOwner(actorId, userId)
        fun blocked(reason: PeopleFailure) = PeopleOptions(inputId, false, reason.name, reason.message)
        if (inputId !in PeopleInput.INPUT_IDS) return blocked(PeopleFailure.INVALID_INPUT)
        if (catalog[inputId]?.deliveryState?.hasHandler != true)
            return PeopleOptions(inputId, false, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val projection = reader.snapshot().state ?: return blocked(PeopleFailure.STATE_UNAVAILABLE)
        val actor = projection.person(actorId) ?: return blocked(PeopleFailure.ACTOR_NOT_FOUND)
        // Employ options have no selected target yet. Search shares its actor/location gates
        // without requiring a target; each discovered candidate still receives the full employ check below.
        val locationInput = if (inputId == PeopleInput.EMPLOY) PeopleInput.SEARCH else inputId
        val locationCheck = PeopleRules.assess(PeopleRequest(actorId, locationInput, null), projection)
        if (locationCheck is PeopleAssessment.Rejected &&
            locationCheck.reason !in setOf(PeopleFailure.TARGET_UNAVAILABLE, PeopleFailure.NO_CANDIDATE))
            return blocked(locationCheck.reason)
        val node = actor.node
        val candidates = when (inputId) {
            PeopleInput.SEARCH -> emptyList()
            PeopleInput.EMPLOY -> {
                val known = try { TalentDiscovery.read(actor.meta) }
                    catch (_: IllegalArgumentException) { return blocked(PeopleFailure.STATE_UNAVAILABLE) }
                projection.people.filter { it.id in known && it.node == node }
            }
            else -> projection.people.filter { it.node == node &&
                runCatching { CaptiveState.read(it.meta) }.getOrNull()?.captorGeneralId == actorId }
        }.sortedBy { it.id }
        val targetOptions = candidates.map { target ->
            when (val check = PeopleRules.assess(PeopleRequest(actorId, inputId, target.id), projection)) {
                is PeopleAssessment.Eligible -> PeopleTargetOption(target.id, target.name, true)
                is PeopleAssessment.Rejected -> PeopleTargetOption(target.id, target.name, false,
                    check.reason.name, check.reason.message)
            }
        }
        val discovery = if (inputId == PeopleInput.SEARCH) locationCheck else null
        val available = when {
            design.status != PeopleDesign.CONFIRMED || catalog[inputId]?.deliveryState?.hasHandler != true -> false
            discovery is PeopleAssessment.Eligible -> true
            inputId != PeopleInput.SEARCH -> targetOptions.any { it.available }
            else -> false
        }
        val failure = when {
            design.status != PeopleDesign.CONFIRMED || catalog[inputId]?.deliveryState?.hasHandler != true ->
                InputRejection.NOT_DELIVERED.name to InputRejection.NOT_DELIVERED.message
            discovery is PeopleAssessment.Rejected -> discovery.reason.name to discovery.reason.message
            inputId != PeopleInput.SEARCH && targetOptions.isEmpty() ->
                PeopleFailure.TARGET_UNAVAILABLE.name to PeopleFailure.TARGET_UNAVAILABLE.message
            inputId != PeopleInput.SEARCH && !available ->
                targetOptions.first().let { it.code to it.reason }
            else -> null
        }
        return PeopleOptions(inputId, available, failure?.first, failure?.second,
            (discovery as? PeopleAssessment.Eligible)?.candidateIds?.size, targetOptions)
    }
}
