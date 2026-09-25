package opensamguk.gameapi.reserve

import opensamguk.logic.domestic.FieldInput
import opensamguk.logic.domestic.FieldFailure
import opensamguk.logic.domestic.FieldAssessment
import opensamguk.logic.domestic.FieldRules
import opensamguk.logic.domestic.FieldEconomyAssessment

import opensamguk.logic.domestic.DomesticDesign
import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.gameapi.read.HwihaDomesticForbidden
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
        try { reader.requireOwner(actorId, ownerUserId.toLong()) }
        catch (_: HwihaDomesticForbidden) { deny("FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.") }
        if (turnIdx !in 0..11) deny("INVALID_TURN_SLOT", "예약 순은 0부터 11까지입니다.")
        val request = FieldInput.parse(actorId, inputId, raw)
            ?: deny(FieldFailure.INVALID_INPUT.name, FieldFailure.INVALID_INPUT.message)
        val snapshot = reader.snapshot()
        val state = snapshot.state ?: if (snapshot.failure == "WRONG_RULE_PROFILE")
            deny(FieldFailure.WRONG_RULE_PROFILE.name, FieldFailure.WRONG_RULE_PROFILE.message)
        else deny(FieldFailure.STATE_UNAVAILABLE.name, FieldFailure.STATE_UNAVAILABLE.message)
        val assessment = FieldRules.assess(request, state)
        if (assessment is FieldAssessment.Rejected) deny(assessment.reason.name, assessment.reason.message)
        val eligible = assessment as FieldAssessment.Eligible
        if (DomesticDesign.CANON.directActionStatus != DomesticDesign.CONFIRMED ||
            catalog[inputId]?.deliveryState?.hasHandler != true)
            deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val economy = FieldRules.assessEconomy(inputId, eligible.person, eligible.county.id,
            snapshot.countyLevels[eligible.county.id], snapshot.warehouseStocks[eligible.county.id], DomesticDesign.CANON)
        if (economy is FieldEconomyAssessment.Rejected) deny(economy.reason.name, economy.reason.message)
        return FieldInput.canonicalJson(request)
    }

    private fun deny(code: String, reason: String): Nothing = throw HwihaAdmissionDenied(code, reason)
}
