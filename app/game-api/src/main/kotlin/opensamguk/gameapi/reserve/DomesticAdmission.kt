package opensamguk.gameapi.reserve

import opensamguk.logic.domestic.DomesticInput

import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.domestic.DomesticFailure
import opensamguk.logic.domestic.DomesticAssessment
import opensamguk.logic.domestic.DomesticRules

import opensamguk.gameapi.read.DomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * 배치·방침·공사 접수 사전검사. 원본 JSON 은 정확한 키만 받고(중복·미지 키 거절), 인증된 제출자가 그 장수의 주인이어야 한다
 * (아니면 [opensamguk.gameapi.read.DomesticForbidden] → 403). 한 REPEATABLE_READ 스냅샷에서 엔진과 같은
 * [DomesticRules] 로 판정하고 canonical 인자만 돌려준다. 통과는 접수 가능성일 뿐이며 엔진이 접수 시점에 다시 검사한다.
 */
@Service
class DomesticAdmission(private val reader: DomesticReader,
    private val catalog: InputCatalog = InputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun canonicalArguments(actorId: Int, ownerUserId: Int, inputId: String, raw: String): String {
        if (ownerUserId <= 0) deny("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        reader.requireOwner(actorId, ownerUserId.toLong())
        val (canonical, assess) = when (inputId) {
            DomesticInput.PLACEMENT -> {
                val request = DomesticInput.parsePlacement(actorId, raw) ?: deny("INVALID_REQUEST", "배치할 카드와 자리를 확인해 주세요.")
                DomesticInput.canonicalJson(request) to { state: DomesticProjection -> DomesticRules.assessPlacement(request, state) }
            }
            DomesticInput.POLICY -> {
                val request = DomesticInput.parsePolicy(actorId, raw) ?: deny("INVALID_REQUEST", "방침 대상과 방침을 확인해 주세요.")
                DomesticInput.canonicalJson(request) to { state: DomesticProjection -> DomesticRules.assessPolicy(request, state) }
            }
            DomesticInput.WORK -> {
                val request = DomesticInput.parseWork(actorId, raw) ?: deny("INVALID_REQUEST", "공사할 현과 공사를 확인해 주세요.")
                DomesticInput.canonicalJson(request) to { state: DomesticProjection -> DomesticRules.assessWork(request, state) }
            }
            DomesticInput.REDUCE -> {
                val request = DomesticInput.parseWork(actorId, raw) ?: deny("INVALID_REQUEST", "감축할 현을 확인해 주세요.")
                DomesticInput.canonicalJson(request) to
                    { state: DomesticProjection -> DomesticRules.assessReduce(request, state) }
            }
            else -> deny(InputRejection.UNKNOWN_INPUT.name, InputRejection.UNKNOWN_INPUT.message)
        }
        val snapshot = reader.snapshot()
        val state = snapshot.state ?: when (snapshot.failure) {
            "WRONG_RULE_PROFILE" -> deny(InputRejection.WRONG_RULE_PROFILE.name, InputRejection.WRONG_RULE_PROFILE.message)
            else -> deny(DomesticFailure.STATE_UNAVAILABLE.name, DomesticFailure.STATE_UNAVAILABLE.message)
        }
        if (inputId == DomesticInput.WORK) {
            val request = DomesticInput.parseWork(actorId, raw)
                ?: deny("INVALID_REQUEST", "공사할 현과 공사를 확인해 주세요.")
            val infrastructure = snapshot.infrastructure ?: deny(DomesticFailure.STATE_UNAVAILABLE.name,
                DomesticFailure.STATE_UNAVAILABLE.message)
            InfrastructureSiteRules.error(request, state, infrastructure)?.let {
                deny("INVALID_INFRASTRUCTURE_SITE", it)
            }
        }
        val assessment = assess(state)
        if (assessment is DomesticAssessment.Rejected) deny(assessment.reason.name, assessment.reason.message)
        if (catalog[inputId]?.deliveryState?.hasHandler != true) deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        return canonical
    }

    private fun deny(code: String, reason: String): Nothing = throw AdmissionDenied(code, reason)
}
