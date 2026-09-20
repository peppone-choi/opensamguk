package opensamguk.gameapi.reserve

import opensamguk.gameapi.precheck.HwihaEnlistmentPrecheckService
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.logic.input.*
import org.springframework.stereotype.Service

class HwihaAdmissionDenied(val code: String, override val message: String) : IllegalArgumentException(message)

/** Validates direct service calls as well as authenticated HTTP requests. No gameplay effects. */
@Service
class HwihaEnlistmentAdmission(
    private val generals: GeneralReadRepository,
    private val precheck: HwihaEnlistmentPrecheckService,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load(),
) {
    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    fun canonicalArguments(generalId: Int, ownerUserId: Int?, turnIdx: Int, raw: String?): String {
        if (ownerUserId == null || ownerUserId <= 0) deny("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        val general = generals.findById(generalId).orElse(null)
        if (general == null || general.userId?.toLongOrNull() != ownerUserId.toLong()) {
            deny("FORBIDDEN", "자신의 장수만 예약할 수 있습니다.")
        }
        if (turnIdx !in 0..11) deny("INVALID_TURN_SLOT", "예약 순은 0부터 11까지입니다.")
        val request = HwihaEnlistmentInput.parse(generalId, raw)
            ?: deny(EnlistmentFailure.INVALID_REQUEST.name, EnlistmentFailure.INVALID_REQUEST.message)
        val assessment = precheck.assess(request)
        if (assessment is EnlistmentAssessment.Rejected) deny(assessment.reason.name, assessment.reason.message)
        if (catalog["action.enlist"]?.deliveryState?.hasHandler != true) {
            deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        }
        return HwihaEnlistmentInput.canonicalJson(request)
    }
    private fun deny(code: String, reason: String): Nothing = throw HwihaAdmissionDenied(code, reason)
}
