package opensamguk.gameapi.reserve

import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** Direct field actions share the domestic read projection and the exact execution rule. */
@Service
class HwihaFieldAdmission(private val reader: HwihaDomesticReader,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun canonicalArguments(inputId: String, actorId: Int, ownerUserId: Int?, turnIdx: Int, raw: String?): String {
        if (ownerUserId == null || ownerUserId <= 0) deny("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        reader.requireOwner(actorId, ownerUserId.toLong())
        if (turnIdx !in 0..11) deny("INVALID_TURN_SLOT", "예약 순은 0부터 11까지입니다.")
        val request = HwihaFieldInput.parse(actorId, inputId, raw)
            ?: deny(HwihaFieldFailure.INVALID_INPUT.name, HwihaFieldFailure.INVALID_INPUT.message)
        val snapshot = reader.snapshot()
        val state = snapshot.state ?: if (snapshot.failure == "WRONG_RULE_PROFILE")
            deny(HwihaFieldFailure.WRONG_RULE_PROFILE.name, HwihaFieldFailure.WRONG_RULE_PROFILE.message)
        else deny(HwihaFieldFailure.STATE_UNAVAILABLE.name, HwihaFieldFailure.STATE_UNAVAILABLE.message)
        val assessment = HwihaFieldRules.assess(request, state)
        if (assessment is HwihaFieldAssessment.Rejected) deny(assessment.reason.name, assessment.reason.message)
        val eligible = assessment as HwihaFieldAssessment.Eligible
        val economy = HwihaFieldRules.assessEconomy(inputId, eligible.person, eligible.county.id,
            snapshot.countyLevels[eligible.county.id], snapshot.warehouseStocks[eligible.county.id], HwihaDomesticDesign.CANON)
        if (economy is HwihaFieldEconomyAssessment.Rejected) deny(economy.reason.name, economy.reason.message)
        if (HwihaDomesticDesign.CANON.directActionStatus != HwihaDomesticDesign.CONFIRMED ||
            catalog[inputId]?.deliveryState?.hasHandler != true)
            deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        return HwihaFieldInput.canonicalJson(request)
    }

    private fun deny(code: String, reason: String): Nothing = throw HwihaAdmissionDenied(code, reason)
}
