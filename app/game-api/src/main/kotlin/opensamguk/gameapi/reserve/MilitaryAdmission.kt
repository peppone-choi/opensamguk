package opensamguk.gameapi.reserve

import opensamguk.logic.domestic.FieldRequest
import opensamguk.logic.domestic.FieldInput
import opensamguk.logic.domestic.FieldAssessment
import opensamguk.logic.domestic.FieldRules

import opensamguk.gameapi.precheck.DeployReadForbidden
import opensamguk.gameapi.precheck.HwihaDeployPrecheckService
import opensamguk.gameapi.read.HwihaDomesticForbidden
import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class HwihaMilitaryAdmission(private val reader: HwihaDomesticReader,
    private val deploy: HwihaDeployPrecheckService,
    private val catalog: InputCatalog = InputCatalog.load(),
    private val design: MilitaryDesign = MilitaryDesign.CANON) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun canonicalArguments(inputId: String, actorId: Int, ownerUserId: Int?, turnIdx: Int, raw: String?): String {
        fun deny(code: String, message: String): Nothing = throw HwihaAdmissionDenied(code, message)
        if (ownerUserId == null || ownerUserId <= 0) deny("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        try { reader.requireOwner(actorId, ownerUserId.toLong()) }
        catch (_: HwihaDomesticForbidden) { deny("FORBIDDEN", "자신의 장수만 예약할 수 있습니다.") }
        if (turnIdx !in 0..11) deny("INVALID_TURN_SLOT", "예약 순은 0부터 11까지입니다.")
        val request = MilitaryInput.parse(actorId, inputId, raw)
            ?: deny(MilitaryFailure.INVALID_INPUT.name, MilitaryFailure.INVALID_INPUT.message)
        if (inputId == MilitaryInput.MUSTER) {
            val check = try { deploy.assessMuster(actorId, ownerUserId.toLong()) }
                catch (_: DeployReadForbidden) { deny("FORBIDDEN", "자신의 장수만 예약할 수 있습니다.") }
            if (check is MusterAssessment.Rejected) deny(check.reason.name, check.reason.message)
        } else {
            val snapshot = reader.snapshot()
            val state = snapshot.state ?: deny(
                if (snapshot.failure == "WRONG_RULE_PROFILE") MilitaryFailure.WRONG_RULE_PROFILE.name
                else MilitaryFailure.STATE_UNAVAILABLE.name, "현재 군사 상태를 확인할 수 없습니다.")
            val geography = FieldRules.assess(FieldRequest(actorId, FieldInput.FARM), state)
            if (geography is FieldAssessment.Rejected)
                deny(geography.reason.name, geography.reason.message)
            val county = (geography as FieldAssessment.Eligible).county
            val levels = snapshot.countyLevels[county.id]
            val check = MilitaryRules.assessCity(request, state, levels?.population,
                levels?.populationMax, snapshot.cityMilitaryTroops[county.id],
                snapshot.cityMilitaryStates[county.id], snapshot.warehouseStocks[county.id], design)
            if (check is CityMilitaryAssessment.Rejected) deny(check.reason.name, check.reason.message)
        }
        if (design.status != MilitaryDesign.CONFIRMED || catalog[inputId]?.deliveryState?.hasHandler != true)
            deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        return MilitaryInput.canonicalJson(request)
    }
}
