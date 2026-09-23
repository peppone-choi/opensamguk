package opensamguk.gameapi.reserve

import opensamguk.gameapi.read.HwihaVisionForbidden
import opensamguk.gameapi.read.HwihaVisionReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service

/** `action.scout` reservation admission. The engine re-checks the same rule at the actor's turn (§5 contract). */
@Service
class HwihaScoutAdmission(private val vision: HwihaVisionReader,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    fun canonicalArguments(generalId: Int, ownerUserId: Int?, turnIdx: Int, raw: String?): String {
        if (ownerUserId == null || ownerUserId <= 0) deny("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        if (turnIdx !in 0..11) deny("INVALID_TURN_SLOT", "예약 순은 0부터 11까지입니다.")
        val input = HwihaScoutInput.parse(generalId, raw)
            ?: deny(ScoutFailure.INVALID_INPUT.name, HwihaScoutRules.reason(ScoutFailure.INVALID_INPUT))
        val assessment = try { vision.assessScout(generalId, ownerUserId.toLong(), input.commanderyId) }
            catch (_: HwihaVisionForbidden) { deny("FORBIDDEN", "자신의 장수만 예약할 수 있습니다.") }
        if (assessment is ScoutAssessment.Rejected) deny(assessment.reason.name, HwihaScoutRules.reason(assessment.reason))
        if (catalog[HwihaScoutInput.INPUT_ID]?.deliveryState?.hasHandler != true)
            deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        return HwihaScoutInput.canonicalJson(input)
    }

    private fun deny(code: String, reason: String): Nothing = throw HwihaAdmissionDenied(code, reason)
}
