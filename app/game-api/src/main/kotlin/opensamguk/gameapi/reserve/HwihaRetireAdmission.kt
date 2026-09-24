package opensamguk.gameapi.reserve

import opensamguk.gameapi.read.HwihaDomesticForbidden
import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class HwihaRetireAdmission(private val reader: HwihaDomesticReader,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun canonicalArguments(actorId: Int, ownerUserId: Int?, turnIdx: Int, raw: String?): String {
        fun deny(code: String, reason: String): Nothing = throw HwihaAdmissionDenied(code, reason)
        if (ownerUserId == null || ownerUserId <= 0) deny("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        try { reader.requireOwner(actorId, ownerUserId.toLong()) }
        catch (_: HwihaDomesticForbidden) { deny("FORBIDDEN", "자신의 장수만 예약할 수 있습니다.") }
        if (turnIdx !in 0..11) deny("INVALID_TURN_SLOT", "예약 순은 0부터 11까지입니다.")
        if (catalog[HwihaRetireInput.INPUT_ID]?.deliveryState?.hasHandler != true)
            deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val request = HwihaRetireInput.parse(actorId, raw)
            ?: deny(HwihaRetireFailure.INVALID_INPUT.name, HwihaRetireFailure.INVALID_INPUT.message)
        val state = reader.snapshot().state ?: deny(HwihaRetireFailure.STATE_UNAVAILABLE.name,
            HwihaRetireFailure.STATE_UNAVAILABLE.message)
        when (val assessed = HwihaRetireRules.assess(request, state)) {
            is HwihaRetireAssessment.Rejected -> deny(assessed.reason.name, assessed.reason.message)
            is HwihaRetireAssessment.Eligible -> Unit
        }
        return HwihaRetireInput.canonicalJson(request)
    }
}
