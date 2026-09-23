package opensamguk.gameapi.reserve

import opensamguk.gameapi.precheck.HwihaDispatchPrecheckService
import opensamguk.logic.input.*
import org.springframework.stereotype.Service

@Service
class HwihaCourtAdmission(private val precheck: HwihaDispatchPrecheckService,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    fun canonicalArguments(actorId: Int, ownerUserId: Int, inputId: String, raw: String): String {
        if (ownerUserId <= 0) throw HwihaAdmissionDenied("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        val (assessment, canonical) = when (inputId) {
            "court.dispatch" -> {
                val request = HwihaDispatchInput.parse(actorId, raw) ?: invalid()
                precheck.assessDispatch(request, ownerUserId.toLong()) to HwihaDispatchInput.canonicalJson(request)
            }
            "court.dispatchReply" -> {
                val request = HwihaDispatchReplyInput.parse(actorId, raw) ?: invalid()
                precheck.assessReply(request, ownerUserId.toLong()) to HwihaDispatchReplyInput.canonicalJson(request)
            }
            // 상사: 카드 소유·창고 잔고는 결정권자의 턴에 엔진이 다시 본다(§4 — 조건이 안 맞으면 비용 없이 무효).
            HwihaRewardInput.INPUT_ID -> {
                val request = HwihaRewardInput.parse(actorId, raw)
                    ?: throw HwihaAdmissionDenied("INVALID_REQUEST", "상사할 카드와 금을 확인해 주세요.")
                null to HwihaRewardInput.canonicalJson(request)
            }
            else -> throw HwihaAdmissionDenied("UNKNOWN_INPUT", "등록되지 않은 조정 입력입니다.")
        }
        if (assessment is DispatchAssessment.Rejected) throw HwihaAdmissionDenied(assessment.reason.name, assessment.reason.message)
        if (catalog[inputId]?.deliveryState?.hasHandler != true) throw HwihaAdmissionDenied("NOT_DELIVERED", "아직 제공되지 않는 입력입니다.")
        return canonical
    }
    private fun invalid(): Nothing = throw HwihaAdmissionDenied("INVALID_REQUEST", "발령 입력을 확인해 주세요.")
}
