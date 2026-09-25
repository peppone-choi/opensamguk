package opensamguk.gameapi.reserve

import opensamguk.gameapi.read.DomesticForbidden
import opensamguk.gameapi.read.DomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class DirectActionAdmission(private val reader: DomesticReader,
    private val catalog: InputCatalog = InputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun canonicalArguments(inputId: String, actorId: Int, ownerUserId: Int?, turnIdx: Int, raw: String?): String {
        fun deny(code: String, reason: String): Nothing = throw AdmissionDenied(code, reason)
        if (ownerUserId == null || ownerUserId <= 0) deny("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        try { reader.requireOwner(actorId, ownerUserId.toLong()) }
        catch (_: DomesticForbidden) { deny("FORBIDDEN", "자신의 장수만 예약할 수 있습니다.") }
        if (turnIdx !in 0..11) deny("INVALID_TURN_SLOT", "예약 순은 0부터 11까지입니다.")
        if (catalog[inputId]?.deliveryState?.hasHandler != true)
            deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val request = DirectInput.parse(actorId, inputId, raw)
            ?: deny(DirectFailure.INVALID_INPUT.name, DirectFailure.INVALID_INPUT.message)
        val state = reader.snapshot().state
            ?: deny(DirectFailure.STATE_UNAVAILABLE.name, DirectFailure.STATE_UNAVAILABLE.message)
        when (val assessed = DirectRules.assess(request, state)) {
            is DirectAssessment.Rejected -> deny(assessed.reason.name, assessed.reason.message)
            is DirectAssessment.Eligible -> Unit
        }
        return DirectInput.canonicalJson(request)
    }
}
