package opensamguk.gameapi.reserve

import opensamguk.gameapi.read.DomesticForbidden
import opensamguk.gameapi.read.DomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class PersonalAdmission(private val reader: DomesticReader,
    private val catalog: InputCatalog = InputCatalog.load(),
    private val design: PersonalDesign = PersonalDesign.CANON) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun canonicalArguments(inputId: String, actorId: Int, ownerUserId: Int?, turnIdx: Int, raw: String?): String {
        fun deny(code: String, reason: String): Nothing = throw AdmissionDenied(code, reason)
        if (ownerUserId == null || ownerUserId <= 0) deny("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        try { reader.requireOwner(actorId, ownerUserId.toLong()) }
        catch (_: DomesticForbidden) { deny("FORBIDDEN", "자신의 장수만 예약할 수 있습니다.") }
        if (turnIdx !in 0..11) deny("INVALID_TURN_SLOT", "예약 순은 0부터 11까지입니다.")
        val request = PersonalInput.parse(actorId, inputId, raw)
            ?: deny(PersonalFailure.INVALID_INPUT.name, PersonalFailure.INVALID_INPUT.message)
        val projection = reader.snapshot().state ?: deny(PersonalFailure.STATE_UNAVAILABLE.name,
            PersonalFailure.STATE_UNAVAILABLE.message)
        when (val assessment = PersonalRules.assess(request, projection)) {
            is PersonalAssessment.Rejected -> deny(assessment.reason.name, assessment.reason.message)
            is PersonalAssessment.Eligible -> Unit
        }
        if (design.status != PersonalDesign.CONFIRMED || catalog[inputId]?.deliveryState?.hasHandler != true)
            deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        return PersonalInput.canonicalJson(request)
    }
}
