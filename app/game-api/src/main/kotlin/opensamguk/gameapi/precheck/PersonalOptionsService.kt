package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class HwihaPersonalStatOption(val stat: String, val available: Boolean,
    val code: String? = null, val reason: String? = null)
data class HwihaPersonalOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val stats: List<HwihaPersonalStatOption> = emptyList())

@Service
class HwihaPersonalOptionsService(private val reader: HwihaDomesticReader,
    private val catalog: InputCatalog = InputCatalog.load(),
    private val design: PersonalDesign = PersonalDesign.CANON) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(inputId: String, actorId: Int, userId: Long): HwihaPersonalOptions {
        reader.requireOwner(actorId, userId)
        fun blocked(reason: PersonalFailure) = HwihaPersonalOptions(inputId, false, reason.name, reason.message)
        if (inputId !in PersonalInput.FIELD_IDS) return blocked(PersonalFailure.INVALID_INPUT)
        val state = reader.snapshot().state ?: return blocked(PersonalFailure.STATE_UNAVAILABLE)
        val choices = if (inputId == PersonalInput.SELF_TRAIN) TrainingStat.entries.toList() else emptyList()
        val checked = choices.map { stat ->
            when (val assessed = PersonalRules.assess(PersonalRequest(actorId, inputId, stat), state)) {
                is PersonalAssessment.Eligible -> HwihaPersonalStatOption(stat.wireName, true)
                is PersonalAssessment.Rejected -> HwihaPersonalStatOption(stat.wireName, false,
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
        return HwihaPersonalOptions(inputId, available, failure?.first, failure?.second, checked)
    }
}
