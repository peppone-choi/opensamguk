package opensamguk.gameapi.reserve

import opensamguk.logic.vision.ScoutInputCodec
import opensamguk.logic.vision.ScoutFailure
import opensamguk.logic.vision.ScoutAssessment
import opensamguk.logic.vision.ScoutRules

import opensamguk.gameapi.read.VisionForbidden
import opensamguk.gameapi.read.VisionReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service

/** `action.scout` reservation admission. The engine re-checks the same rule at the actor's turn (§5 contract). */
@Service
class ScoutAdmission(private val vision: VisionReader,
    private val catalog: InputCatalog = InputCatalog.load()) {
    fun canonicalArguments(generalId: Int, ownerUserId: Int?, turnIdx: Int, raw: String?): String {
        if (ownerUserId == null || ownerUserId <= 0) deny("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        if (turnIdx !in 0..11) deny("INVALID_TURN_SLOT", "예약 순은 0부터 11까지입니다.")
        val input = ScoutInputCodec.parse(generalId, raw)
            ?: deny(ScoutFailure.INVALID_INPUT.name, ScoutRules.reason(ScoutFailure.INVALID_INPUT))
        val assessment = try { vision.assessScout(generalId, ownerUserId.toLong(), input.commanderyId) }
            catch (_: VisionForbidden) { deny("FORBIDDEN", "자신의 장수만 예약할 수 있습니다.") }
        if (assessment is ScoutAssessment.Rejected) deny(assessment.reason.name, ScoutRules.reason(assessment.reason))
        if (catalog[ScoutInputCodec.INPUT_ID]?.deliveryState?.hasHandler != true)
            deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        return ScoutInputCodec.canonicalJson(input)
    }

    private fun deny(code: String, reason: String): Nothing = throw AdmissionDenied(code, reason)
}
