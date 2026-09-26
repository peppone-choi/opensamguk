package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.DomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class RetireSuccessorOption(val generalId: Int, val name: String, val available: Boolean,
    val code: String? = null, val reason: String? = null)
data class RetireOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val successors: List<RetireSuccessorOption> = emptyList())

@Service
class RetireOptionsService(private val reader: DomesticReader,
    private val catalog: InputCatalog = InputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(actorId: Int, userId: Long): RetireOptions {
        reader.requireOwner(actorId, userId)
        if (catalog[RetireInput.INPUT_ID]?.deliveryState?.hasHandler != true)
            return RetireOptions(RetireInput.INPUT_ID, false,
                InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val state = reader.snapshot().state ?: return RetireOptions(RetireInput.INPUT_ID,
            false, RetireFailure.STATE_UNAVAILABLE.name, RetireFailure.STATE_UNAVAILABLE.message)
        val candidates = state.cards.filter { it.masterId == actorId && it.generalId != null }
            .mapNotNull { card -> state.person(card.generalId!!)?.let { person ->
                val assessed = RetireRules.assess(RetireRequest(actorId, person.id), state)
                when (assessed) {
                    is RetireAssessment.Eligible -> RetireSuccessorOption(person.id, person.name, true)
                    is RetireAssessment.Rejected -> RetireSuccessorOption(person.id, person.name, false,
                        assessed.reason.name, assessed.reason.message)
                }
            } }.sortedBy { it.generalId }
        val available = candidates.any { it.available }
        val failure = when {
            !available && candidates.isEmpty() -> RetireFailure.SUCCESSOR_NOT_RETAINER.name to
                RetireFailure.SUCCESSOR_NOT_RETAINER.message
            !available -> candidates.first().let { it.code to it.reason }
            else -> null
        }
        return RetireOptions(RetireInput.INPUT_ID, available, failure?.first, failure?.second, candidates)
    }
}
