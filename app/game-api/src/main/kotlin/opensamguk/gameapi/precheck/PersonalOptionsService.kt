package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.DomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class PersonalStatOption(val stat: String, val available: Boolean,
    val code: String? = null, val reason: String? = null)
data class PersonalOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val stats: List<PersonalStatOption> = emptyList())

@Service
class PersonalOptionsService(private val reader: DomesticReader,
    private val catalog: InputCatalog = InputCatalog.load(),
    private val design: PersonalDesign = PersonalDesign.CANON) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(inputId: String, actorId: Int, userId: Long): PersonalOptions {
        reader.requireOwner(actorId, userId)
        fun blocked(reason: PersonalFailure) = PersonalOptions(inputId, false, reason.name, reason.message)
        if (inputId !in PersonalInput.FIELD_IDS) return blocked(PersonalFailure.INVALID_INPUT)
        val state = reader.snapshot().state ?: return blocked(PersonalFailure.STATE_UNAVAILABLE)
        val choices = if (inputId == PersonalInput.SELF_TRAIN) TrainingStat.entries.toList() else emptyList()
        val checked = choices.map { stat ->
            when (val assessed = PersonalRules.assess(PersonalRequest(actorId, inputId, stat), state)) {
                is PersonalAssessment.Eligible -> PersonalStatOption(stat.wireName, true)
                is PersonalAssessment.Rejected -> PersonalStatOption(stat.wireName, false,
                    assessed.reason.name, assessed.reason.message)
            }
        }
        val single = if (inputId == PersonalInput.SELF_TRAIN) null
            else PersonalRules.assess(PersonalRequest(actorId, inputId), state)
        val available = design.status == PersonalDesign.CONFIRMED &&
            catalog[inputId]?.deliveryState?.hasHandler == true &&
            (single is PersonalAssessment.Eligible || checked.any { it.available })
        val failure = when {
            design.status != PersonalDesign.CONFIRMED || catalog[inputId]?.deliveryState?.hasHandler != true ->
                InputRejection.NOT_DELIVERED.name to InputRejection.NOT_DELIVERED.message
            single is PersonalAssessment.Rejected -> single.reason.name to single.reason.message
            !available && checked.isNotEmpty() -> checked.first().let { it.code to it.reason }
            else -> null
        }
        return PersonalOptions(inputId, available, failure?.first, failure?.second, checked)
    }
}
