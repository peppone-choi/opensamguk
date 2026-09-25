package opensamguk.gameapi.reserve

import opensamguk.gameapi.precheck.TravelPrecheckService
import opensamguk.gameapi.precheck.TravelReadForbidden
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class TravelAdmission(private val precheck: TravelPrecheckService,
    private val catalog: InputCatalog = InputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun canonicalArguments(inputId: String, generalId: Int, ownerUserId: Int?, turnIdx: Int, raw: String?): String {
        if (ownerUserId == null || ownerUserId <= 0) deny("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        try { precheck.requireOwner(generalId, ownerUserId.toLong()) }
        catch (_: TravelReadForbidden) { deny("FORBIDDEN", "자신의 장수만 예약할 수 있습니다.") }
        if (turnIdx !in 0..11) deny("INVALID_TURN_SLOT", "예약 순은 0부터 11까지입니다.")
        val request = TravelInput.parse(generalId, inputId, raw)
            ?: deny(TravelFailure.INVALID_INPUT.name, TravelFailure.INVALID_INPUT.message)
        val assessment = precheck.assess(request, ownerUserId.toLong())
        if (assessment is TravelAssessment.Rejected) deny(assessment.reason.name, assessment.reason.message)
        if (catalog[inputId]?.deliveryState?.hasHandler != true)
            deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        return TravelInput.canonicalJson(request)
    }

    private fun deny(code: String, reason: String): Nothing = throw AdmissionDenied(code, reason)
}
