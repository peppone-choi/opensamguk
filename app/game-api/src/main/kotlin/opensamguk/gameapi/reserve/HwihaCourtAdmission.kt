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
    private val catalog: InputCatalog = InputCatalog.load(),
    private val reader: HwihaDomesticReader? = null) {
    fun canonicalArguments(actorId: Int, ownerUserId: Int, inputId: String, raw: String): String {
        if (ownerUserId <= 0) throw HwihaAdmissionDenied("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        // Standing domestic inputs share the immediate channel (no 12-phase slot), with their own admission.
        if (inputId in DomesticInput.INPUT_IDS) return (domestic
            ?: throw HwihaAdmissionDenied("POLICY_UNAVAILABLE", "내정 입력 정책을 확인할 수 없습니다."))
            .canonicalArguments(actorId, ownerUserId, inputId, raw)
        val (assessment, canonical) = when (inputId) {
            "court.dispatch" -> {
                val request = DispatchInput.parse(actorId, raw) ?: invalid()
                precheck.assessDispatch(request, ownerUserId.toLong()) to DispatchInput.canonicalJson(request)
            }
            "court.dispatchReply" -> {
                val request = DispatchReplyInput.parse(actorId, raw) ?: invalid()
                precheck.assessReply(request, ownerUserId.toLong()) to DispatchReplyInput.canonicalJson(request)
            }
            // 상사: 카드 소유·창고 잔고는 결정권자의 턴에 엔진이 다시 본다(§4 — 조건이 안 맞으면 비용 없이 무효).
            RewardInput.INPUT_ID -> {
                val request = RewardInput.parse(actorId, raw)
                    ?: throw HwihaAdmissionDenied("INVALID_REQUEST", "상사할 카드와 금을 확인해 주세요.")
                null to RewardInput.canonicalJson(request)
            }
            PoliticalConsent.COURT_INPUT_ID -> {
                try { reader?.requireOwner(actorId, ownerUserId.toLong())
                    ?: throw HwihaAdmissionDenied(PoliticalFailure.STATE_UNAVAILABLE.name, PoliticalFailure.STATE_UNAVAILABLE.message) }
                catch (_: HwihaDomesticForbidden) { throw HwihaAdmissionDenied("FORBIDDEN", "자신의 장수만 응답할 수 있습니다.") }
                val consent = PoliticalConsent.parse(actorId, raw)
                    ?: throw HwihaAdmissionDenied(PoliticalFailure.INVALID_INPUT.name, PoliticalFailure.INVALID_INPUT.message)
                val state = reader?.snapshot()?.state
                    ?: throw HwihaAdmissionDenied(PoliticalFailure.STATE_UNAVAILABLE.name, PoliticalFailure.STATE_UNAVAILABLE.message)
                PoliticalRules.assessConsent(actorId, consent, state)?.let {
                    throw HwihaAdmissionDenied(it.name, it.message)
                }
                null to PoliticalConsent.canonicalJson(consent)
            }
            in CourtInput.INPUT_IDS -> {
                try { reader?.requireOwner(actorId, ownerUserId.toLong())
                    ?: throw HwihaAdmissionDenied(CourtFailure.STATE_UNAVAILABLE.name,
                        CourtFailure.STATE_UNAVAILABLE.message) }
                catch (_: HwihaDomesticForbidden) { throw HwihaAdmissionDenied("FORBIDDEN", "자신의 장수만 결정할 수 있습니다.") }
                val json = CourtInput.canonical(actorId, inputId, raw)
                    ?: throw HwihaAdmissionDenied(CourtFailure.INVALID_INPUT.name,
                        CourtFailure.INVALID_INPUT.message)
                val state = reader?.snapshot()?.state
                    ?: throw HwihaAdmissionDenied(CourtFailure.STATE_UNAVAILABLE.name,
                        CourtFailure.STATE_UNAVAILABLE.message)
                when (val result = CourtRules.assess(actorId, inputId, json, state)) {
                    is CourtAssessment.Rejected -> throw HwihaAdmissionDenied(result.reason.name, result.reason.message)
                    is CourtAssessment.Eligible -> null to json
                }
            }
            in StratagemInput.INPUT_IDS -> {
                try { reader?.requireOwner(actorId, ownerUserId.toLong())
                    ?: throw HwihaAdmissionDenied(StratagemFailure.STATE_UNAVAILABLE.name,
                        StratagemFailure.STATE_UNAVAILABLE.message) }
                catch (_: HwihaDomesticForbidden) { throw HwihaAdmissionDenied("FORBIDDEN", "자신의 장수만 계책을 낼 수 있습니다.") }
                val request = StratagemInput.parse(actorId, inputId, raw)
                    ?: throw HwihaAdmissionDenied(StratagemFailure.INVALID_INPUT.name,
                        StratagemFailure.INVALID_INPUT.message)
                val state = reader?.snapshot()?.state
                    ?: throw HwihaAdmissionDenied(StratagemFailure.STATE_UNAVAILABLE.name,
                        StratagemFailure.STATE_UNAVAILABLE.message)
                when (val result = StratagemRules.assess(request, state)) {
                    is StratagemAssessment.Rejected -> throw HwihaAdmissionDenied(result.reason.name, result.reason.message)
                    is StratagemAssessment.Eligible -> null to StratagemInput.canonicalJson(request)
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
