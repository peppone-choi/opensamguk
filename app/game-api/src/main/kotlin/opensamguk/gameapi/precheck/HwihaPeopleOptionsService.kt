package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class HwihaPeopleTargetOption(val generalId: Int, val name: String, val available: Boolean,
    val code: String? = null, val reason: String? = null)
data class HwihaPeopleOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val undiscoveredCount: Int? = null, val targets: List<HwihaPeopleTargetOption> = emptyList())

/** Only discovered free people and the actor's own captives are named to the caller. */
@Service
class HwihaPeopleOptionsService(private val reader: HwihaDomesticReader,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load(),
    private val design: HwihaPeopleDesign = HwihaPeopleDesign.CANON) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(inputId: String, actorId: Int, userId: Long): HwihaPeopleOptions {
        reader.requireOwner(actorId, userId)
        fun blocked(reason: HwihaPeopleFailure) = HwihaPeopleOptions(inputId, false, reason.name, reason.message)
        if (inputId !in HwihaPeopleInput.INPUT_IDS) return blocked(HwihaPeopleFailure.INVALID_INPUT)
        val projection = reader.snapshot().state ?: return blocked(HwihaPeopleFailure.STATE_UNAVAILABLE)
        val actor = projection.person(actorId) ?: return blocked(HwihaPeopleFailure.ACTOR_NOT_FOUND)
        val locationCheck = HwihaPeopleRules.assess(HwihaPeopleRequest(actorId, inputId, null), projection)
        if (locationCheck is HwihaPeopleAssessment.Rejected &&
            locationCheck.reason !in setOf(HwihaPeopleFailure.TARGET_UNAVAILABLE, HwihaPeopleFailure.NO_CANDIDATE))
            return blocked(locationCheck.reason)
        val node = actor.node
        val candidates = when (inputId) {
            HwihaPeopleInput.SEARCH -> emptyList()
            HwihaPeopleInput.EMPLOY -> {
                val known = try { HwihaTalentDiscovery.read(actor.meta) }
                    catch (_: IllegalArgumentException) { return blocked(HwihaPeopleFailure.STATE_UNAVAILABLE) }
                projection.people.filter { it.id in known && it.node == node }
            }
            else -> projection.people.filter { it.node == node &&
                (it.meta["hwihaCaptive"] as? Map<*, *>)?.get("captorGeneralId") == actorId }
        }.sortedBy { it.id }
        val targetOptions = candidates.map { target ->
            when (val check = HwihaPeopleRules.assess(HwihaPeopleRequest(actorId, inputId, target.id), projection)) {
                is HwihaPeopleAssessment.Eligible -> HwihaPeopleTargetOption(target.id, target.name, true)
                is HwihaPeopleAssessment.Rejected -> HwihaPeopleTargetOption(target.id, target.name, false,
                    check.reason.name, check.reason.message)
            }
        }
        val discovery = if (inputId == HwihaPeopleInput.SEARCH) locationCheck else null
        val available = when {
            design.status != HwihaPeopleDesign.CONFIRMED || catalog[inputId]?.deliveryState?.hasHandler != true -> false
            discovery is HwihaPeopleAssessment.Eligible -> true
            inputId != HwihaPeopleInput.SEARCH -> targetOptions.any { it.available }
            else -> false
        }
        val failure = when {
            design.status != HwihaPeopleDesign.CONFIRMED || catalog[inputId]?.deliveryState?.hasHandler != true ->
                InputRejection.NOT_DELIVERED.name to InputRejection.NOT_DELIVERED.message
            discovery is HwihaPeopleAssessment.Rejected -> discovery.reason.name to discovery.reason.message
            inputId != HwihaPeopleInput.SEARCH && targetOptions.isEmpty() ->
                HwihaPeopleFailure.TARGET_UNAVAILABLE.name to HwihaPeopleFailure.TARGET_UNAVAILABLE.message
            inputId != HwihaPeopleInput.SEARCH && !available ->
                targetOptions.first().let { it.code to it.reason }
            else -> null
        }
        return HwihaPeopleOptions(inputId, available, failure?.first, failure?.second,
            (discovery as? HwihaPeopleAssessment.Eligible)?.candidateIds?.size, targetOptions)
    }
}
