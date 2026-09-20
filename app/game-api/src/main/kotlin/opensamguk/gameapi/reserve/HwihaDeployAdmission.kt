package opensamguk.gameapi.reserve

import opensamguk.gameapi.precheck.*
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class HwihaDeployAdmission(private val precheck: HwihaDeployPrecheckService,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun canonicalArguments(generalId: Int, ownerUserId: Int?, turnIdx: Int, raw: String?): String {
        if (ownerUserId == null || ownerUserId <= 0) deny("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        try { precheck.requireOwner(generalId, ownerUserId.toLong()) }
        catch (_: DeployReadForbidden) { deny("FORBIDDEN", "자신의 장수만 예약할 수 있습니다.") }
        if (turnIdx !in 0..11) deny("INVALID_TURN_SLOT", "예약 순은 0부터 11까지입니다.")
        val request = HwihaDeployInput.parse(generalId, raw)
            ?: deny(DeploymentFailure.INVALID_INPUT.name, HwihaDeployRules.reason(DeploymentFailure.INVALID_INPUT))
        val assessment = precheck.assess(request, ownerUserId.toLong())
        if (assessment is DeploymentAssessment.Rejected) deny(assessment.reason.name, HwihaDeployRules.reason(assessment.reason))
        if (catalog[HwihaDeployInput.INPUT_ID]?.deliveryState?.hasHandler != true)
            deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        return HwihaDeployInput.canonicalJson(request)
    }
    private fun deny(code: String, reason: String): Nothing = throw HwihaAdmissionDenied(code, reason)
}
