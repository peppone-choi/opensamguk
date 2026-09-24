package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class HwihaPoliticalOption(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null)

@Service
class HwihaPoliticalOptionsService(private val reader: HwihaDomesticReader,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(actorId: Int, userId: Long): List<HwihaPoliticalOption> {
        reader.requireOwner(actorId, userId)
        val state = reader.snapshot().state
        return HwihaPoliticalRules.SUPPORTED_IDS.map { inputId ->
            val assessment = state?.let { HwihaPoliticalRules.assess(HwihaPoliticalRequest(actorId, inputId), it) }
            val failure = when {
                catalog[inputId]?.deliveryState?.hasHandler != true ->
                    InputRejection.NOT_DELIVERED.name to InputRejection.NOT_DELIVERED.message
                state == null -> HwihaPoliticalFailure.STATE_UNAVAILABLE.name to HwihaPoliticalFailure.STATE_UNAVAILABLE.message
                assessment is HwihaPoliticalAssessment.Rejected -> assessment.reason.name to assessment.reason.message
                else -> null
            }
            HwihaPoliticalOption(inputId, failure == null, failure?.first, failure?.second)
        }
    }
}
