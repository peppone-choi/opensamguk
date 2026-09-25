package opensamguk.gameapi.reserve

import opensamguk.gameapi.precheck.EnlistmentPrecheckService
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.logic.input.*
import org.springframework.stereotype.Service

class AdmissionDenied(val code: String, override val message: String) : IllegalArgumentException(message)

/** Validates direct service calls as well as authenticated HTTP requests. No gameplay effects. */
@Service
class EnlistmentAdmission(
    private val generals: GeneralReadRepository,
    private val precheck: EnlistmentPrecheckService,
    private val catalog: InputCatalog = InputCatalog.load(),
) {
    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    fun canonicalArguments(generalId: Int, ownerUserId: Int?, turnIdx: Int, raw: String?,
        inputId: String = "action.enlist"): String {
        if (ownerUserId == null || ownerUserId <= 0) deny("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        val general = generals.findById(generalId).orElse(null)
        if (general == null || general.userId?.toLongOrNull() != ownerUserId.toLong()) {
            deny("FORBIDDEN", "자신의 장수만 예약할 수 있습니다.")
        }
        if (turnIdx !in 0..11) deny("INVALID_TURN_SLOT", "예약 순은 0부터 11까지입니다.")
        val request = EnlistmentInput.parse(generalId, inputId, raw)
            ?: deny(EnlistmentFailure.INVALID_REQUEST.name, EnlistmentFailure.INVALID_REQUEST.message)
        val assessment = precheck.assess(request)
        if (assessment is EnlistmentAssessment.Rejected) deny(assessment.reason.name, assessment.reason.message)
        if (catalog[inputId]?.deliveryState?.hasHandler != true) {
            deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        }
        return EnlistmentInput.canonicalJson(request)
    }
    private fun deny(code: String, reason: String): Nothing = throw AdmissionDenied(code, reason)
}
