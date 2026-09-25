package opensamguk.gameapi.reserve

import opensamguk.logic.domestic.DomesticInput

import opensamguk.gameapi.precheck.HwihaDispatchPrecheckService
import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.gameapi.read.HwihaDomesticForbidden
import opensamguk.logic.input.*
import org.springframework.stereotype.Service

@Service
class HwihaCourtAdmission(private val precheck: HwihaDispatchPrecheckService,
    private val domestic: HwihaDomesticAdmission? = null,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load(),
    private val reader: HwihaDomesticReader? = null) {
    fun canonicalArguments(actorId: Int, ownerUserId: Int, inputId: String, raw: String): String {
        if (ownerUserId <= 0) throw HwihaAdmissionDenied("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        // Standing domestic inputs share the immediate channel (no 12-phase slot), with their own admission.
        if (inputId in DomesticInput.INPUT_IDS) return (domestic
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
            // 상사: 카드 소유·창고 잔고는 결정권자의 턴에 엔진이 다시 본다(§4 — 조건이 안 맞으면 비용 없이 무효).
            HwihaRewardInput.INPUT_ID -> {
                val request = HwihaRewardInput.parse(actorId, raw)
                    ?: throw HwihaAdmissionDenied("INVALID_REQUEST", "상사할 카드와 금을 확인해 주세요.")
                null to HwihaRewardInput.canonicalJson(request)
            }
            HwihaPoliticalConsent.COURT_INPUT_ID -> {
                try { reader?.requireOwner(actorId, ownerUserId.toLong())
                    ?: throw HwihaAdmissionDenied(HwihaPoliticalFailure.STATE_UNAVAILABLE.name, HwihaPoliticalFailure.STATE_UNAVAILABLE.message) }
                catch (_: HwihaDomesticForbidden) { throw HwihaAdmissionDenied("FORBIDDEN", "자신의 장수만 응답할 수 있습니다.") }
                val consent = HwihaPoliticalConsent.parse(actorId, raw)
                    ?: throw HwihaAdmissionDenied(HwihaPoliticalFailure.INVALID_INPUT.name, HwihaPoliticalFailure.INVALID_INPUT.message)
                val state = reader?.snapshot()?.state
                    ?: throw HwihaAdmissionDenied(HwihaPoliticalFailure.STATE_UNAVAILABLE.name, HwihaPoliticalFailure.STATE_UNAVAILABLE.message)
                HwihaPoliticalRules.assessConsent(actorId, consent, state)?.let {
                    throw HwihaAdmissionDenied(it.name, it.message)
                }
                null to HwihaPoliticalConsent.canonicalJson(consent)
            }
            in HwihaLegacyCourtInput.INPUT_IDS -> {
                try { reader?.requireOwner(actorId, ownerUserId.toLong())
                    ?: throw HwihaAdmissionDenied(HwihaLegacyCourtFailure.STATE_UNAVAILABLE.name,
                        HwihaLegacyCourtFailure.STATE_UNAVAILABLE.message) }
                catch (_: HwihaDomesticForbidden) { throw HwihaAdmissionDenied("FORBIDDEN", "자신의 장수만 결정할 수 있습니다.") }
                val json = HwihaLegacyCourtInput.canonical(actorId, inputId, raw)
                    ?: throw HwihaAdmissionDenied(HwihaLegacyCourtFailure.INVALID_INPUT.name,
                        HwihaLegacyCourtFailure.INVALID_INPUT.message)
                val state = reader?.snapshot()?.state
                    ?: throw HwihaAdmissionDenied(HwihaLegacyCourtFailure.STATE_UNAVAILABLE.name,
                        HwihaLegacyCourtFailure.STATE_UNAVAILABLE.message)
                when (val result = HwihaLegacyCourtRules.assess(actorId, inputId, json, state)) {
                    is HwihaLegacyCourtAssessment.Rejected -> throw HwihaAdmissionDenied(result.reason.name, result.reason.message)
                    is HwihaLegacyCourtAssessment.Eligible -> null to json
                }
            }
            in HwihaLegacyStratagemInput.INPUT_IDS -> {
                try { reader?.requireOwner(actorId, ownerUserId.toLong())
                    ?: throw HwihaAdmissionDenied(HwihaLegacyStratagemFailure.STATE_UNAVAILABLE.name,
                        HwihaLegacyStratagemFailure.STATE_UNAVAILABLE.message) }
                catch (_: HwihaDomesticForbidden) { throw HwihaAdmissionDenied("FORBIDDEN", "자신의 장수만 계책을 낼 수 있습니다.") }
                val request = HwihaLegacyStratagemInput.parse(actorId, inputId, raw)
                    ?: throw HwihaAdmissionDenied(HwihaLegacyStratagemFailure.INVALID_INPUT.name,
                        HwihaLegacyStratagemFailure.INVALID_INPUT.message)
                val state = reader?.snapshot()?.state
                    ?: throw HwihaAdmissionDenied(HwihaLegacyStratagemFailure.STATE_UNAVAILABLE.name,
                        HwihaLegacyStratagemFailure.STATE_UNAVAILABLE.message)
                when (val result = HwihaLegacyStratagemRules.assess(request, state)) {
                    is HwihaLegacyStratagemAssessment.Rejected -> throw HwihaAdmissionDenied(result.reason.name, result.reason.message)
                    is HwihaLegacyStratagemAssessment.Eligible -> null to HwihaLegacyStratagemInput.canonicalJson(request)
                }
            }
            else -> throw HwihaAdmissionDenied("UNKNOWN_INPUT", "등록되지 않은 조정 입력입니다.")
        }
        if (assessment is DispatchAssessment.Rejected) throw HwihaAdmissionDenied(assessment.reason.name, assessment.reason.message)
        if (catalog[inputId]?.deliveryState?.hasHandler != true) throw HwihaAdmissionDenied("NOT_DELIVERED", "아직 제공되지 않는 입력입니다.")
        return canonical
    }
    private fun invalid(): Nothing = throw HwihaAdmissionDenied("INVALID_REQUEST", "발령 입력을 확인해 주세요.")
}
