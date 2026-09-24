package opensamguk.logic.input

enum class HwihaPoliticalFailure(val message: String) {
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
    ALREADY_PROCESSED("이 순에는 이미 정치 행동을 실행했습니다."),
}

sealed interface HwihaPoliticalAssessment {
    data class Eligible(val actor: DomesticPerson, val county: DomesticCounty?, val wasLord: Boolean) : HwihaPoliticalAssessment
    data class Rejected(val reason: HwihaPoliticalFailure) : HwihaPoliticalAssessment
}

/** Pure authority and current-county checks for the nation changing direct actions. */
object HwihaPoliticalRules {
    val SUPPORTED_IDS = setOf(HwihaPoliticalInput.RESIGN, HwihaPoliticalInput.RISE,
        HwihaPoliticalInput.FOUND_STATE,
        HwihaPoliticalInput.INDEPENDENCE, HwihaPoliticalInput.DISSOLVE)

    fun assess(request: HwihaPoliticalRequest, state: HwihaDomesticProjection): HwihaPoliticalAssessment {
        fun reject(reason: HwihaPoliticalFailure) = HwihaPoliticalAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return reject(HwihaPoliticalFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || request.inputId !in SUPPORTED_IDS || request.targetGeneralId != null)
            return reject(HwihaPoliticalFailure.INVALID_INPUT)
        val actor = state.person(request.actorId) ?: return reject(HwihaPoliticalFailure.ACTOR_NOT_FOUND)
        if (actor.inBattle) return reject(HwihaPoliticalFailure.BATTLE_PENDING)
        val lord = try { HwihaLordStatus.read(actor.meta) }
            catch (_: IllegalArgumentException) { return reject(HwihaPoliticalFailure.STATE_UNAVAILABLE) }
        val node = actor.node ?: return reject(HwihaPoliticalFailure.POSITION_UNAVAILABLE)
        if (state.landProvinceIds?.contains(node) != true) return reject(HwihaPoliticalFailure.STATE_UNAVAILABLE)
        val counties = state.counties.filter { it.provinceId == node }
        if (counties.size > 1) return reject(HwihaPoliticalFailure.STATE_UNAVAILABLE)
        val county = counties.singleOrNull()
        if (request.inputId in setOf(HwihaPoliticalInput.RISE, HwihaPoliticalInput.INDEPENDENCE) && county == null)
            return reject(HwihaPoliticalFailure.COUNTY_UNAVAILABLE)
        val renown = try { HwihaPersonPolicyState.read(actor.meta)?.renownCapacity }
            catch (_: IllegalArgumentException) { return reject(HwihaPoliticalFailure.STATE_UNAVAILABLE) }
        when (request.inputId) {
            HwihaPoliticalInput.RESIGN -> {
                if (actor.nationId <= 0) return reject(HwihaPoliticalFailure.NOT_A_SUBJECT)
                if (lord) return reject(HwihaPoliticalFailure.ALREADY_LORD)
            }
            HwihaPoliticalInput.RISE -> {
                if (actor.nationId != 0) return reject(HwihaPoliticalFailure.NOT_FREE)
                if (renown == null) return reject(HwihaPoliticalFailure.STATE_UNAVAILABLE)
                if (renown < 50) return reject(HwihaPoliticalFailure.INSUFFICIENT_RENOWN)
                if (county!!.nationId != 0) return reject(HwihaPoliticalFailure.COUNTY_NOT_AVAILABLE)
            }
            HwihaPoliticalInput.INDEPENDENCE -> {
                if (actor.nationId <= 0) return reject(HwihaPoliticalFailure.NOT_A_SUBJECT)
                if (lord) return reject(HwihaPoliticalFailure.ALREADY_LORD)
                if (renown == null) return reject(HwihaPoliticalFailure.STATE_UNAVAILABLE)
                if (renown < 50) return reject(HwihaPoliticalFailure.INSUFFICIENT_RENOWN)
                if (county!!.nationId != actor.nationId) return reject(HwihaPoliticalFailure.COUNTY_NOT_AVAILABLE)
            }
            HwihaPoliticalInput.DISSOLVE -> {
                if (actor.nationId <= 0 || !lord) return reject(HwihaPoliticalFailure.NOT_LORD)
                if (state.nation(actor.nationId) == null) return reject(HwihaPoliticalFailure.STATE_UNAVAILABLE)
            }
            HwihaPoliticalInput.FOUND_STATE -> {
                if (actor.nationId <= 0 || !lord) return reject(HwihaPoliticalFailure.NOT_LORD)
                val nation = state.nation(actor.nationId) ?: return reject(HwihaPoliticalFailure.STATE_UNAVAILABLE)
                if (nation.level > 0) return reject(HwihaPoliticalFailure.ALREADY_FOUNDED)
                if (nation.capitalCityId == null) return reject(HwihaPoliticalFailure.STATE_UNAVAILABLE)
            }
        }
        return HwihaPoliticalAssessment.Eligible(actor, county, lord)
    }
}
