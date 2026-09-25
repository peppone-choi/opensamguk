package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticProjection

enum class PoliticalFailure(val message: String) {
    WRONG_RULE_PROFILE("이 월드에서는 정치 행동을 사용할 수 없습니다."),
    INVALID_INPUT("정치 행동 인자를 확인할 수 없습니다."),
    ACTOR_NOT_FOUND("행동할 장수를 찾을 수 없습니다."),
    BATTLE_PENDING("조우 처리가 끝나야 정치 행동을 할 수 있습니다."),
    POSITION_UNAVAILABLE("장수의 현재 육상 위치를 확인할 수 없습니다."),
    COUNTY_UNAVAILABLE("현재 위치에 행정 縣이 없습니다."),
    STATE_UNAVAILABLE("현재 세력·명망 상태를 확인할 수 없습니다."),
    NOT_A_SUBJECT("하야하려면 섬기는 세력이 있어야 합니다."),
    NOT_FREE("거병하려면 재야여야 합니다."),
    NOT_LORD("주공만 세력을 해산할 수 있습니다."),
    ALREADY_FOUNDED("이미 건국한 세력입니다."),
    ALREADY_LORD("이미 주공인 장수는 이 행동을 할 수 없습니다."),
    INSUFFICIENT_RENOWN("거병·독립에는 명망 50이 필요합니다."),
    COUNTY_NOT_AVAILABLE("현재 縣을 이 행동으로 차지할 수 없습니다."),
    TARGET_NOT_FOUND("대상 장수를 찾을 수 없습니다."),
    TARGET_NOT_HUMAN("대상 장수가 직접 동의할 수 없습니다."),
    SAME_NATION_REQUIRED("같은 세력의 장수를 선택해 주세요."),
    SAME_PROVINCE_REQUIRED("같은 省에 있는 장수를 선택해 주세요."),
    CONSENT_REQUIRED("대상 장수의 수락이 필요합니다."),
    CONSENT_DECLINED("대상 장수가 거절했습니다."),
    ALREADY_BOUND("이미 결의한 사이입니다."),
    ALREADY_PROCESSED("이 순에는 이미 정치 행동을 실행했습니다."),
}

sealed interface PoliticalAssessment {
    data class Eligible(val actor: DomesticPerson, val county: DomesticCounty?, val wasLord: Boolean) : PoliticalAssessment
    data class Rejected(val reason: PoliticalFailure) : PoliticalAssessment
}

/** Pure authority and current-county checks for the nation changing direct actions. */
object PoliticalRules {
    val SUPPORTED_IDS = PoliticalInput.INPUT_IDS

    fun assess(request: PoliticalRequest, state: DomesticProjection): PoliticalAssessment {
        fun reject(reason: PoliticalFailure) = PoliticalAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return reject(PoliticalFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || request.inputId !in SUPPORTED_IDS ||
            (request.inputId in PoliticalInput.NO_ARGUMENT_IDS) != (request.targetGeneralId == null))
            return reject(PoliticalFailure.INVALID_INPUT)
        val actor = state.person(request.actorId) ?: return reject(PoliticalFailure.ACTOR_NOT_FOUND)
        if (actor.inBattle) return reject(PoliticalFailure.BATTLE_PENDING)
        val lord = try { LordStatus.read(actor.meta) }
            catch (_: IllegalArgumentException) { return reject(PoliticalFailure.STATE_UNAVAILABLE) }
        val node = actor.node ?: return reject(PoliticalFailure.POSITION_UNAVAILABLE)
        if (state.landProvinceIds?.contains(node) != true) return reject(PoliticalFailure.STATE_UNAVAILABLE)
        val counties = state.counties.filter { it.provinceId == node }
        if (counties.size > 1) return reject(PoliticalFailure.STATE_UNAVAILABLE)
        val county = counties.singleOrNull()
        if (request.inputId in setOf(PoliticalInput.RISE, PoliticalInput.INDEPENDENCE) && county == null)
            return reject(PoliticalFailure.COUNTY_UNAVAILABLE)
        val renown = try { PersonPolicyState.read(actor.meta)?.renownCapacity }
            catch (_: IllegalArgumentException) { return reject(PoliticalFailure.STATE_UNAVAILABLE) }
        when (request.inputId) {
            PoliticalInput.RESIGN -> {
                if (actor.nationId <= 0) return reject(PoliticalFailure.NOT_A_SUBJECT)
                if (lord) return reject(PoliticalFailure.ALREADY_LORD)
            }
            PoliticalInput.RISE -> {
                if (actor.nationId != 0) return reject(PoliticalFailure.NOT_FREE)
                if (renown == null) return reject(PoliticalFailure.STATE_UNAVAILABLE)
                if (renown < PoliticalDesign.CANON.riseMinimumRenown)
                    return reject(PoliticalFailure.INSUFFICIENT_RENOWN)
                if (county!!.nationId != 0) return reject(PoliticalFailure.COUNTY_NOT_AVAILABLE)
            }
            PoliticalInput.INDEPENDENCE -> {
                if (actor.nationId <= 0) return reject(PoliticalFailure.NOT_A_SUBJECT)
                if (lord) return reject(PoliticalFailure.ALREADY_LORD)
                if (renown == null) return reject(PoliticalFailure.STATE_UNAVAILABLE)
                if (renown < PoliticalDesign.CANON.independenceMinimumRenown)
                    return reject(PoliticalFailure.INSUFFICIENT_RENOWN)
                if (county!!.nationId != actor.nationId) return reject(PoliticalFailure.COUNTY_NOT_AVAILABLE)
            }
            PoliticalInput.DISSOLVE -> {
                if (actor.nationId <= 0 || !lord) return reject(PoliticalFailure.NOT_LORD)
                if (state.nation(actor.nationId) == null) return reject(PoliticalFailure.STATE_UNAVAILABLE)
            }
            PoliticalInput.FOUND_STATE -> {
                if (actor.nationId <= 0 || !lord) return reject(PoliticalFailure.NOT_LORD)
                val nation = state.nation(actor.nationId) ?: return reject(PoliticalFailure.STATE_UNAVAILABLE)
                if (nation.level > 0) return reject(PoliticalFailure.ALREADY_FOUNDED)
                if (nation.capitalCityId == null) return reject(PoliticalFailure.STATE_UNAVAILABLE)
            }
            PoliticalInput.ABDICATE, PoliticalInput.OATH -> {
                val target = state.person(request.targetGeneralId!!)
                    ?: return reject(PoliticalFailure.TARGET_NOT_FOUND)
                val relation = try { relationFailure(request.inputId, actor, target, state) }
                    catch (_: IllegalArgumentException) { return reject(PoliticalFailure.STATE_UNAVAILABLE) }
                when (val failure = relation) {
                    null -> Unit
                    else -> return reject(failure)
                }
                val consent = try { PoliticalConsent.read(target.meta) }
                    catch (_: IllegalArgumentException) { return reject(PoliticalFailure.STATE_UNAVAILABLE) }
                if (consent?.issuerGeneralId != actor.id || consent.inputId != request.inputId)
                    return reject(PoliticalFailure.CONSENT_REQUIRED)
                if (!consent.accepted) return reject(PoliticalFailure.CONSENT_DECLINED)
            }
        }
        return PoliticalAssessment.Eligible(actor, county, lord)
    }

    /** Used by the recipient's immediate reply, before storing their decision. */
    fun assessConsent(targetId: Int, consent: PoliticalConsent, state: DomesticProjection): PoliticalFailure? {
        if (state.profile != RuleProfile.HWIHA) return PoliticalFailure.WRONG_RULE_PROFILE
        val target = state.person(targetId) ?: return PoliticalFailure.ACTOR_NOT_FOUND
        val issuer = state.person(consent.issuerGeneralId) ?: return PoliticalFailure.TARGET_NOT_FOUND
        if (target.inBattle || issuer.inBattle) return PoliticalFailure.BATTLE_PENDING
        return try { relationFailure(consent.inputId, issuer, target, state) }
            catch (_: IllegalArgumentException) { PoliticalFailure.STATE_UNAVAILABLE }
    }

    private fun relationFailure(inputId: String, issuer: DomesticPerson, target: DomesticPerson,
        state: DomesticProjection): PoliticalFailure? {
        if (issuer.id == target.id) return PoliticalFailure.INVALID_INPUT
        if (!target.userOwned) return PoliticalFailure.TARGET_NOT_HUMAN
        if (inputId == PoliticalInput.ABDICATE) {
            if (issuer.nationId <= 0 || !LordStatus.read(issuer.meta)) return PoliticalFailure.NOT_LORD
            if (issuer.nationId != target.nationId) return PoliticalFailure.SAME_NATION_REQUIRED
            if (target.inBattle) return PoliticalFailure.BATTLE_PENDING
            val chiefId = state.nation(issuer.nationId)?.chiefGeneralId
            if ((chiefId != null && chiefId != issuer.id) ||
                state.people.count { it.nationId == issuer.nationId && it.officerLevel == 12 } != 1 ||
                issuer.officerLevel != 12) return PoliticalFailure.STATE_UNAVAILABLE
        } else if (inputId == PoliticalInput.OATH) {
            if (issuer.node == null || issuer.node !in (state.landProvinceIds ?: emptySet()))
                return PoliticalFailure.POSITION_UNAVAILABLE
            if (issuer.node != target.node) return PoliticalFailure.SAME_PROVINCE_REQUIRED
            if (OathBonds.read(issuer.meta).contains(target.id) || OathBonds.read(target.meta).contains(issuer.id))
                return PoliticalFailure.ALREADY_BOUND
        } else return PoliticalFailure.INVALID_INPUT
        return null
    }
}
