package opensamguk.gameapi.reserve

import opensamguk.logic.domestic.DomesticInput

import opensamguk.gameapi.precheck.DispatchPrecheckService
import opensamguk.gameapi.read.DomesticReader
import opensamguk.gameapi.read.DomesticForbidden
import opensamguk.logic.input.*
import opensamguk.logic.war.CampaignBalance
import org.springframework.stereotype.Service

@Service
class CourtAdmission(private val precheck: DispatchPrecheckService,
    private val domestic: DomesticAdmission? = null,
    private val catalog: InputCatalog = InputCatalog.load(),
    private val reader: DomesticReader? = null) {
    fun canonicalArguments(actorId: Int, ownerUserId: Int, inputId: String, raw: String): String {
        if (ownerUserId <= 0) throw AdmissionDenied("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        // Standing domestic inputs share the immediate channel (no 12-phase slot), with their own admission.
        if (inputId in DomesticInput.INPUT_IDS) return (domestic
            ?: throw AdmissionDenied("POLICY_UNAVAILABLE", "내정 입력 정책을 확인할 수 없습니다."))
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
            // 접수에서 현재 충성의 금 상한을 검사하고, 실행 시점의 충성·소유·잔고는 엔진이 다시 본다.
            RewardInput.INPUT_ID -> {
                val request = RewardInput.parse(actorId, raw)
                    ?: throw AdmissionDenied("INVALID_REQUEST", "상사할 카드와 금을 확인해 주세요.")
                val rewardReader = reader
                    ?: throw AdmissionDenied("STATE_UNAVAILABLE", "상사할 카드의 상태를 확인할 수 없습니다.")
                val loyalty = try { rewardReader.rewardLoyalty(actorId, ownerUserId.toLong(), request.retainerId) }
                    catch (_: DomesticForbidden) { throw AdmissionDenied("FORBIDDEN", "자신의 장수만 상사를 내릴 수 있습니다.") }
                    ?: throw AdmissionDenied("CARD_UNAVAILABLE", "직접 거느린 인물 카드에만 상사를 내릴 수 있습니다.")
                if (request.money < CampaignBalance.REWARD_MONEY_PER_LOYALTY)
                    throw AdmissionDenied("TOO_SMALL", "상사 금이 너무 적어 충성이 오르지 않습니다.")
                if (request.money > RewardMoneyLimit.maximumFor(loyalty))
                    throw AdmissionDenied("REWARD_OVER_CAP", "현재 충성에서 내릴 수 있는 상사 금을 넘었습니다.")
                null to RewardInput.canonicalJson(request)
            }
            CaptiveReleaseInput.INPUT_ID -> {
                try { reader?.requireOwner(actorId, ownerUserId.toLong())
                    ?: throw AdmissionDenied(PeopleFailure.STATE_UNAVAILABLE.name, PeopleFailure.STATE_UNAVAILABLE.message) }
                catch (_: DomesticForbidden) { throw AdmissionDenied("FORBIDDEN", "자신이 잡은 포로만 석방할 수 있습니다.") }
                val request = CaptiveReleaseInput.parse(actorId, raw)
                    ?: throw AdmissionDenied(PeopleFailure.INVALID_INPUT.name, PeopleFailure.INVALID_INPUT.message)
                val state = reader?.snapshot()?.state
                    ?: throw AdmissionDenied(PeopleFailure.STATE_UNAVAILABLE.name, PeopleFailure.STATE_UNAVAILABLE.message)
                CaptiveReleaseRules.assess(request, state)?.let { throw AdmissionDenied(it.name, it.message) }
                null to CaptiveReleaseInput.canonicalJson(request)
            }
            PoliticalConsent.COURT_INPUT_ID -> {
                try { reader?.requireOwner(actorId, ownerUserId.toLong())
                    ?: throw AdmissionDenied(PoliticalFailure.STATE_UNAVAILABLE.name, PoliticalFailure.STATE_UNAVAILABLE.message) }
                catch (_: DomesticForbidden) { throw AdmissionDenied("FORBIDDEN", "자신의 장수만 응답할 수 있습니다.") }
                val consent = PoliticalConsent.parse(actorId, raw)
                    ?: throw AdmissionDenied(PoliticalFailure.INVALID_INPUT.name, PoliticalFailure.INVALID_INPUT.message)
                val state = reader?.snapshot()?.state
                    ?: throw AdmissionDenied(PoliticalFailure.STATE_UNAVAILABLE.name, PoliticalFailure.STATE_UNAVAILABLE.message)
                PoliticalRules.assessConsent(actorId, consent, state)?.let {
                    throw AdmissionDenied(it.name, it.message)
                }
                null to PoliticalConsent.canonicalJson(consent)
            }
            in CourtInput.INPUT_IDS -> {
                try { reader?.requireOwner(actorId, ownerUserId.toLong())
                    ?: throw AdmissionDenied(CourtFailure.STATE_UNAVAILABLE.name,
                        CourtFailure.STATE_UNAVAILABLE.message) }
                catch (_: DomesticForbidden) { throw AdmissionDenied("FORBIDDEN", "자신의 장수만 결정할 수 있습니다.") }
                val json = CourtInput.canonical(actorId, inputId, raw)
                    ?: throw AdmissionDenied(CourtFailure.INVALID_INPUT.name,
                        CourtFailure.INVALID_INPUT.message)
                val state = reader?.snapshot()?.state
                    ?: throw AdmissionDenied(CourtFailure.STATE_UNAVAILABLE.name,
                        CourtFailure.STATE_UNAVAILABLE.message)
                if (inputId == DiplomacyInput.OFFER_PEACE && state.person(actorId)?.meta?.containsKey("queuedCourt") == true)
                    throw AdmissionDenied(CourtFailure.ALREADY_QUEUED.name, CourtFailure.ALREADY_QUEUED.message)
                when (val result = CourtRules.assess(actorId, inputId, json, state)) {
                    is CourtAssessment.Rejected -> throw AdmissionDenied(result.reason.name, result.reason.message)
                    is CourtAssessment.Eligible -> null to json
                }
            }
            in StratagemInput.INPUT_IDS -> {
                try { reader?.requireOwner(actorId, ownerUserId.toLong())
                    ?: throw AdmissionDenied(StratagemFailure.STATE_UNAVAILABLE.name,
                        StratagemFailure.STATE_UNAVAILABLE.message) }
                catch (_: DomesticForbidden) { throw AdmissionDenied("FORBIDDEN", "자신의 장수만 계책을 낼 수 있습니다.") }
                val request = StratagemInput.parse(actorId, inputId, raw)
                    ?: throw AdmissionDenied(StratagemFailure.INVALID_INPUT.name,
                        StratagemFailure.INVALID_INPUT.message)
                val state = reader?.snapshot()?.state
                    ?: throw AdmissionDenied(StratagemFailure.STATE_UNAVAILABLE.name,
                        StratagemFailure.STATE_UNAVAILABLE.message)
                when (val result = StratagemRules.assess(request, state)) {
                    is StratagemAssessment.Rejected -> throw AdmissionDenied(result.reason.name, result.reason.message)
                    is StratagemAssessment.Eligible -> null to StratagemInput.canonicalJson(request)
                }
            }
            else -> throw AdmissionDenied("UNKNOWN_INPUT", "등록되지 않은 조정 입력입니다.")
        }
        if (assessment is DispatchAssessment.Rejected) throw AdmissionDenied(assessment.reason.name, assessment.reason.message)
        if (catalog[inputId]?.deliveryState?.hasHandler != true) throw AdmissionDenied("NOT_DELIVERED", "아직 제공되지 않는 입력입니다.")
        return canonical
    }
    private fun invalid(): Nothing = throw AdmissionDenied("INVALID_REQUEST", "발령 입력을 확인해 주세요.")
}
