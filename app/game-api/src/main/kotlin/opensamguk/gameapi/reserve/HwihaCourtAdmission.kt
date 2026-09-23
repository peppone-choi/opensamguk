package opensamguk.gameapi.reserve

import opensamguk.gameapi.precheck.HwihaDispatchPrecheckService
import opensamguk.logic.input.*
import org.springframework.stereotype.Service

@Service
class HwihaCourtAdmission(private val precheck: HwihaDispatchPrecheckService,
    private val domestic: HwihaDomesticAdmission? = null,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    fun canonicalArguments(actorId: Int, ownerUserId: Int, inputId: String, raw: String): String {
        if (ownerUserId <= 0) throw HwihaAdmissionDenied("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        // Standing domestic inputs share the immediate channel (no 12-phase slot), with their own admission.
        if (inputId in HwihaDomesticInput.INPUT_IDS) return (domestic
            ?: throw HwihaAdmissionDenied("POLICY_UNAVAILABLE", "내정 입력 정책을 확인할 수 없습니다."))
            .canonicalArguments(actorId, ownerUserId, inputId, raw)
        val (assessment, canonical) = when (inputId) {
            "court.dispatch" -> {
                val request = HwihaDispatchInput.parse(actorId, raw) ?: invalid()
                precheck.assessDispatch(request, ownerUserId.toLong()) to HwihaDispatchInput.canonicalJson(request)
            }
            "court.dispatchReply" -> {
                val request = HwihaDispatchReplyInput.parse(actorId, raw) ?: invalid()
                precheck.assessReply(request, ownerUserId.toLong()) to HwihaDispatchReplyInput.canonicalJson(request)
            }
            else -> throw HwihaAdmissionDenied("UNKNOWN_INPUT", "등록되지 않은 조정 입력입니다.")
        }
        if (assessment is DispatchAssessment.Rejected) throw HwihaAdmissionDenied(assessment.reason.name, assessment.reason.message)
        if (catalog[inputId]?.deliveryState?.hasHandler != true) throw HwihaAdmissionDenied("NOT_DELIVERED", "아직 제공되지 않는 입력입니다.")
        return canonical
    }
    private fun invalid(): Nothing = throw HwihaAdmissionDenied("INVALID_REQUEST", "발령 입력을 확인해 주세요.")
}
