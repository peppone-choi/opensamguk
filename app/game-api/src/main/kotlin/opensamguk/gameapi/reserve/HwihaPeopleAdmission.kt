package opensamguk.gameapi.reserve

import opensamguk.gameapi.read.HwihaDomesticForbidden
import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class HwihaPeopleAdmission(private val reader: HwihaDomesticReader,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load(),
    private val design: HwihaPeopleDesign = HwihaPeopleDesign.CANON) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun canonicalArguments(inputId: String, actorId: Int, ownerUserId: Int?, turnIdx: Int, raw: String?): String {
        fun deny(code: String, reason: String): Nothing = throw HwihaAdmissionDenied(code, reason)
        if (ownerUserId == null || ownerUserId <= 0) deny("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        try { reader.requireOwner(actorId, ownerUserId.toLong()) }
        catch (_: HwihaDomesticForbidden) { deny("FORBIDDEN", "자신의 장수만 예약할 수 있습니다.") }
        if (turnIdx !in 0..11) deny("INVALID_TURN_SLOT", "예약 순은 0부터 11까지입니다.")
        val request = HwihaPeopleInput.parse(actorId, inputId, raw)
            ?: deny(HwihaPeopleFailure.INVALID_INPUT.name, HwihaPeopleFailure.INVALID_INPUT.message)
        val projection = reader.snapshot().state ?: deny(HwihaPeopleFailure.STATE_UNAVAILABLE.name,
            HwihaPeopleFailure.STATE_UNAVAILABLE.message)
        when (val assessment = HwihaPeopleRules.assess(request, projection)) {
            is HwihaPeopleAssessment.Rejected -> deny(assessment.reason.name, assessment.reason.message)
            is HwihaPeopleAssessment.Eligible -> Unit
        }
        if (design.status != HwihaPeopleDesign.CONFIRMED || catalog[inputId]?.deliveryState?.hasHandler != true)
            deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        return HwihaPeopleInput.canonicalJson(request)
    }
}
