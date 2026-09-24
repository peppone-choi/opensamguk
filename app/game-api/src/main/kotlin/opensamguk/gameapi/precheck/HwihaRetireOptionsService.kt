package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class HwihaRetireSuccessorOption(val generalId: Int, val name: String, val available: Boolean,
    val code: String? = null, val reason: String? = null)
data class HwihaRetireOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val successors: List<HwihaRetireSuccessorOption> = emptyList())

@Service
class HwihaRetireOptionsService(private val reader: HwihaDomesticReader,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(actorId: Int, userId: Long): HwihaRetireOptions {
        reader.requireOwner(actorId, userId)
        if (catalog[HwihaRetireInput.INPUT_ID]?.deliveryState?.hasHandler != true)
            return HwihaRetireOptions(HwihaRetireInput.INPUT_ID, false,
                InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val state = reader.snapshot().state ?: return HwihaRetireOptions(HwihaRetireInput.INPUT_ID,
            false, HwihaRetireFailure.STATE_UNAVAILABLE.name, HwihaRetireFailure.STATE_UNAVAILABLE.message)
        val candidates = state.cards.filter { it.masterId == actorId && it.generalId != null }
            .mapNotNull { card -> state.person(card.generalId!!)?.let { person ->
                val assessed = HwihaRetireRules.assess(HwihaRetireRequest(actorId, person.id), state)
                when (assessed) {
                    is HwihaRetireAssessment.Eligible -> HwihaRetireSuccessorOption(person.id, person.name, true)
                    is HwihaRetireAssessment.Rejected -> HwihaRetireSuccessorOption(person.id, person.name, false,
                        assessed.reason.name, assessed.reason.message)
                }
            } }.sortedBy { it.generalId }
        val available = candidates.any { it.available }
        val failure = when {
            !available && candidates.isEmpty() -> HwihaRetireFailure.SUCCESSOR_NOT_RETAINER.name to
                HwihaRetireFailure.SUCCESSOR_NOT_RETAINER.message
            !available -> candidates.first().let { it.code to it.reason }
            else -> null
        }
        return HwihaRetireOptions(HwihaRetireInput.INPUT_ID, available, failure?.first, failure?.second, candidates)
    }
}
