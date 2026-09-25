package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.DomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class StratagemActionChoice(val label: String, val arguments: Map<String, Int>,
    val available: Boolean, val code: String? = null, val reason: String? = null)
data class StratagemActionOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val choices: List<StratagemActionChoice> = emptyList())

@Service
class StratagemActionOptionsService(private val reader: DomesticReader,
    private val catalog: InputCatalog = InputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(actorId: Int, userId: Long, inputId: String): StratagemActionOptions {
        reader.requireOwner(actorId, userId)
        if (inputId !in StratagemInput.INPUT_IDS || catalog[inputId]?.deliveryState?.hasHandler != true)
            return StratagemActionOptions(inputId, false, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val state = reader.snapshot().state ?: return StratagemActionOptions(inputId, false,
            StratagemFailure.STATE_UNAVAILABLE.name, StratagemFailure.STATE_UNAVAILABLE.message)
        val actor = state.person(actorId) ?: return StratagemActionOptions(inputId, false,
            StratagemFailure.ACTOR_NOT_FOUND.name, StratagemFailure.ACTOR_NOT_FOUND.message)
        val local = state.counties.singleOrNull { it.provinceId == actor.node && it.nationId == actor.nationId }
        val requests: List<Pair<String, StratagemInput.Request>> = when (inputId) {
            StratagemInput.LAST_STAND -> listOf("본인" to StratagemInput.Request(actorId, inputId))
            StratagemInput.PROVOKE_RIVALRY -> state.nations.filter { it.id != actor.nationId }
                .sortedBy { it.id }.flatMapIndexed { index, first ->
                    state.nations.filter { it.id != actor.nationId }.sortedBy { it.id }.drop(index + 1).map { second ->
                        "${first.name} ↔ ${second.name}" to StratagemInput.Request(actorId, inputId,
                            firstNationId = first.id, secondNationId = second.id)
                    }
                }
            in StratagemInput.OWN_COUNTY_IDS -> local?.let { listOf(it.name to
                StratagemInput.Request(actorId, inputId, targetCountyId = it.id)) }.orEmpty()
            else -> state.countyAdjacency[local?.id].orEmpty().sorted().mapNotNull { id ->
                state.county(id)?.takeIf { it.nationId > 0 && it.nationId != actor.nationId }?.let { county ->
                    county.name to StratagemInput.Request(actorId, inputId, targetCountyId = county.id)
                }
            }
        }
        val choices = requests.map { (label, request) ->
            val result = StratagemRules.assess(request, state)
            val failure = (result as? StratagemAssessment.Rejected)?.reason
            val args = when (request.inputId) {
                StratagemInput.LAST_STAND -> emptyMap()
                StratagemInput.PROVOKE_RIVALRY -> mapOf("firstNationId" to request.firstNationId!!,
                    "secondNationId" to request.secondNationId!!)
                else -> mapOf("targetCountyId" to request.targetCountyId!!)
            }
            StratagemActionChoice(label, args, failure == null, failure?.name, failure?.message)
        }
        val available = choices.any { it.available }
        val reason = if (available) null else choices.firstOrNull()?.let { it.code to it.reason }
            ?: (StratagemFailure.TARGET_UNAVAILABLE.name to StratagemFailure.TARGET_UNAVAILABLE.message)
        return StratagemActionOptions(inputId, available, reason?.first, reason?.second, choices)
    }
}
