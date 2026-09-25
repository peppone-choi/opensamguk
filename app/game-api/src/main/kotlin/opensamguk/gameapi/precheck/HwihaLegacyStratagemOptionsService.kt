package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class HwihaLegacyStratagemChoice(val label: String, val arguments: Map<String, Int>,
    val available: Boolean, val code: String? = null, val reason: String? = null)
data class HwihaLegacyStratagemOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val choices: List<HwihaLegacyStratagemChoice> = emptyList())

@Service
class HwihaLegacyStratagemOptionsService(private val reader: HwihaDomesticReader,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(actorId: Int, userId: Long, inputId: String): HwihaLegacyStratagemOptions {
        reader.requireOwner(actorId, userId)
        if (inputId !in HwihaLegacyStratagemInput.INPUT_IDS || catalog[inputId]?.deliveryState?.hasHandler != true)
            return HwihaLegacyStratagemOptions(inputId, false, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val state = reader.snapshot().state ?: return HwihaLegacyStratagemOptions(inputId, false,
            HwihaLegacyStratagemFailure.STATE_UNAVAILABLE.name, HwihaLegacyStratagemFailure.STATE_UNAVAILABLE.message)
        val actor = state.person(actorId) ?: return HwihaLegacyStratagemOptions(inputId, false,
            HwihaLegacyStratagemFailure.ACTOR_NOT_FOUND.name, HwihaLegacyStratagemFailure.ACTOR_NOT_FOUND.message)
        val local = state.counties.singleOrNull { it.provinceId == actor.node && it.nationId == actor.nationId }
        val requests: List<Pair<String, HwihaLegacyStratagemInput.Request>> = when (inputId) {
            HwihaLegacyStratagemInput.LAST_STAND -> listOf("본인" to HwihaLegacyStratagemInput.Request(actorId, inputId))
            HwihaLegacyStratagemInput.PROVOKE_RIVALRY -> state.nations.filter { it.id != actor.nationId }
                .sortedBy { it.id }.flatMapIndexed { index, first ->
                    state.nations.filter { it.id != actor.nationId }.sortedBy { it.id }.drop(index + 1).map { second ->
                        "${first.name} ↔ ${second.name}" to HwihaLegacyStratagemInput.Request(actorId, inputId,
                            firstNationId = first.id, secondNationId = second.id)
                    }
                }
            in HwihaLegacyStratagemInput.OWN_COUNTY_IDS -> local?.let { listOf(it.name to
                HwihaLegacyStratagemInput.Request(actorId, inputId, targetCountyId = it.id)) }.orEmpty()
            else -> state.countyAdjacency[local?.id].orEmpty().sorted().mapNotNull { id ->
                state.county(id)?.takeIf { it.nationId > 0 && it.nationId != actor.nationId }?.let { county ->
                    county.name to HwihaLegacyStratagemInput.Request(actorId, inputId, targetCountyId = county.id)
                }
            }
        }
        val choices = requests.map { (label, request) ->
            val result = HwihaLegacyStratagemRules.assess(request, state)
            val failure = (result as? HwihaLegacyStratagemAssessment.Rejected)?.reason
            val args = when (request.inputId) {
                HwihaLegacyStratagemInput.LAST_STAND -> emptyMap()
                HwihaLegacyStratagemInput.PROVOKE_RIVALRY -> mapOf("firstNationId" to request.firstNationId!!,
                    "secondNationId" to request.secondNationId!!)
                else -> mapOf("targetCountyId" to request.targetCountyId!!)
            }
            HwihaLegacyStratagemChoice(label, args, failure == null, failure?.name, failure?.message)
        }
        val available = choices.any { it.available }
        val reason = if (available) null else choices.firstOrNull()?.let { it.code to it.reason }
            ?: (HwihaLegacyStratagemFailure.TARGET_UNAVAILABLE.name to HwihaLegacyStratagemFailure.TARGET_UNAVAILABLE.message)
        return HwihaLegacyStratagemOptions(inputId, available, reason?.first, reason?.second, choices)
    }
}
