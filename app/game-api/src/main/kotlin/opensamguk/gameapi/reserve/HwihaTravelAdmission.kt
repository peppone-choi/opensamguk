package opensamguk.gameapi.reserve

import opensamguk.gameapi.precheck.HwihaTravelPrecheckService
import opensamguk.gameapi.precheck.TravelReadForbidden
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class HwihaTravelAdmission(private val precheck: HwihaTravelPrecheckService,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun canonicalArguments(inputId: String, generalId: Int, ownerUserId: Int?, turnIdx: Int, raw: String?): String {
        if (ownerUserId == null || ownerUserId <= 0) deny("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        try { precheck.requireOwner(generalId, ownerUserId.toLong()) }
        catch (_: TravelReadForbidden) { deny("FORBIDDEN", "자신의 장수만 예약할 수 있습니다.") }
        if (turnIdx !in 0..11) deny("INVALID_TURN_SLOT", "예약 순은 0부터 11까지입니다.")
        val request = HwihaTravelInput.parse(generalId, inputId, raw)
            ?: deny(HwihaTravelFailure.INVALID_INPUT.name, HwihaTravelFailure.INVALID_INPUT.message)
        val assessment = precheck.assess(request, ownerUserId.toLong())
        if (assessment is HwihaTravelAssessment.Rejected) deny(assessment.reason.name, assessment.reason.message)
        if (catalog[inputId]?.deliveryState?.hasHandler != true)
            deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        return HwihaTravelInput.canonicalJson(request)
    }

    private fun deny(code: String, reason: String): Nothing = throw HwihaAdmissionDenied(code, reason)
}
