package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.DomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class PeopleTargetOption(val generalId: Int, val name: String, val available: Boolean,
    val code: String? = null, val reason: String? = null)
data class PeopleOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val undiscoveredCount: Int? = null, val targets: List<PeopleTargetOption> = emptyList())

/** Only discovered free people and the actor's own captives are named to the caller. */
@Service
class PeopleOptionsService(private val reader: DomesticReader,
    private val catalog: InputCatalog = InputCatalog.load(),
    private val design: PeopleDesign = PeopleDesign.CANON) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(inputId: String, actorId: Int, userId: Long): PeopleOptions {
        reader.requireOwner(actorId, userId)
        fun blocked(reason: PeopleFailure) = PeopleOptions(inputId, false, reason.name, reason.message)
        if (inputId !in PeopleInput.INPUT_IDS) return blocked(PeopleFailure.INVALID_INPUT)
        if (catalog[inputId]?.deliveryState?.hasHandler != true)
            return PeopleOptions(inputId, false, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val projection = reader.snapshot().state ?: return blocked(PeopleFailure.STATE_UNAVAILABLE)
        val actor = projection.person(actorId) ?: return blocked(PeopleFailure.ACTOR_NOT_FOUND)
        val locationCheck = PeopleRules.assess(PeopleRequest(actorId, inputId, null), projection)
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
                (it.meta["hwihaCaptive"] as? Map<*, *>)?.get("captorGeneralId") == actorId }
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
