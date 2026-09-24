package opensamguk.logic.input

import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.diplomacy.DiplomacyState

/** The eleven old court orders use one strict input and one pure admission/execution gate. */
object HwihaLegacyCourtInput {
    const val INSTITUTION = "court.institution"
    val INPUT_IDS = HwihaCourtExpansionInput.INPUT_IDS + HwihaCourtResourceInput.INPUT_IDS +
        HwihaDiplomacyInput.INPUT_IDS + INSTITUTION

    fun canonical(actorId: Int, inputId: String, raw: String?): String? = when (inputId) {
        in HwihaCourtExpansionInput.INPUT_IDS -> HwihaCourtExpansionInput.parse(actorId, inputId, raw)
            ?.let(HwihaCourtExpansionInput::canonicalJson)
        in HwihaCourtResourceInput.INPUT_IDS -> HwihaCourtResourceInput.parse(actorId, inputId, raw)
            ?.let(HwihaCourtResourceInput::canonicalJson)
        in HwihaDiplomacyInput.INPUT_IDS -> HwihaDiplomacyInput.parse(actorId, inputId, raw)
            ?.let(HwihaDiplomacyInput::canonicalJson)
        INSTITUTION -> if (raw != null && runCatching { HwihaFlatArguments(raw).read().isEmpty() }.getOrDefault(false)) "{}" else null
        else -> null
    }
}

enum class HwihaLegacyCourtFailure(val message: String) {
    WRONG_RULE_PROFILE("휘하 월드에서만 사용할 수 있습니다."), INVALID_INPUT("조정 입력의 대상을 확인해 주세요."),
    ACTOR_NOT_FOUND("장수를 찾을 수 없습니다."), NOT_RULER("소속 세력의 군주만 결정할 수 있습니다."),
    COUNTY_UNAVAILABLE("소유한 행정 현을 선택해 주세요."), CAPITAL_REQUIRED("현재 도읍은 버릴 수 없습니다."),
    LAST_COUNTY("마지막 행정 현은 버릴 수 없습니다."), TARGET_UNAVAILABLE("대상 장수나 세력을 찾을 수 없습니다."),
    CORPS_UNAVAILABLE("직접 거느린 출전 군단을 찾을 수 없습니다."), ENVOY_REQUIRED("대상 세력에 도착한 사자 카드가 필요합니다."),
    INSUFFICIENT_STOCK("국고 또는 대상의 자원이 부족합니다."), STOCK_OVERFLOW("자원 보유 한도를 넘습니다."),
    ALREADY_AT_WAR("이미 전쟁 중입니다."), NOT_AT_WAR("전쟁 중인 세력이 아닙니다."),
    AGREEMENT_UNAVAILABLE("유효한 불가침 관계가 없습니다."), STATE_UNAVAILABLE("조정 상태를 확인할 수 없습니다."),
    ALREADY_QUEUED("이미 실행 대기 중인 조정 결정이 있습니다.")
}

data class HwihaLegacyCourtReady(val actor: DomesticPerson, val nation: DomesticNation,
    val county: DomesticCounty? = null, val targetPerson: DomesticPerson? = null,
    val targetNation: DomesticNation? = null, val corps: HwihaDeployedCorps? = null,
    val sourceStock: HwihaResources? = null, val destinationStock: HwihaResources? = null)
sealed interface HwihaLegacyCourtAssessment {
    data class Eligible(val ready: HwihaLegacyCourtReady) : HwihaLegacyCourtAssessment
    data class Rejected(val reason: HwihaLegacyCourtFailure) : HwihaLegacyCourtAssessment
}

object HwihaLegacyCourtRules {
    fun assess(actorId: Int, inputId: String, raw: String, state: HwihaDomesticProjection): HwihaLegacyCourtAssessment {
        fun fail(reason: HwihaLegacyCourtFailure) = HwihaLegacyCourtAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return fail(HwihaLegacyCourtFailure.WRONG_RULE_PROFILE)
        if (HwihaLegacyCourtInput.canonical(actorId, inputId, raw) == null) return fail(HwihaLegacyCourtFailure.INVALID_INPUT)
        val actor = state.person(actorId) ?: return fail(HwihaLegacyCourtFailure.ACTOR_NOT_FOUND)
        val nation = state.nation(actor.nationId) ?: return fail(HwihaLegacyCourtFailure.NOT_RULER)
        if (HwihaDomesticRules.rulerOf(nation.id, state)?.id != actorId) return fail(HwihaLegacyCourtFailure.NOT_RULER)
        fun eligible(county: DomesticCounty? = null, person: DomesticPerson? = null,
            targetNation: DomesticNation? = null, corps: HwihaDeployedCorps? = null,
            source: HwihaResources? = null, destination: HwihaResources? = null) =
            HwihaLegacyCourtAssessment.Eligible(HwihaLegacyCourtReady(actor, nation, county, person, targetNation, corps, source, destination))
        return try {
            when (inputId) {
                HwihaCourtExpansionInput.RELEASE_CORPS -> {
                    val request = HwihaCourtExpansionInput.parse(actorId, inputId, raw) as HwihaCourtExpansionRequest.ReleaseCorps
                    val person = state.person(request.targetGeneralId)?.takeIf { it.nationId == nation.id }
                        ?: return fail(HwihaLegacyCourtFailure.TARGET_UNAVAILABLE)
                    val corps = HwihaDomesticRules.deployedCorps(state).singleOrNull {
                        it.commanderGeneralId == person.id && it.ownerGeneralId == actorId
                    } ?: return fail(HwihaLegacyCourtFailure.CORPS_UNAVAILABLE)
                    eligible(person = person, corps = corps)
                }
                HwihaCourtExpansionInput.ABANDON_COUNTY, HwihaCourtExpansionInput.MOVE_CAPITAL -> {
                    val request = HwihaCourtExpansionInput.parse(actorId, inputId, raw) as HwihaCourtExpansionRequest.County
                    val county = state.county(request.countyId)?.takeIf { it.nationId == nation.id }
                        ?: return fail(HwihaLegacyCourtFailure.COUNTY_UNAVAILABLE)
                    if (inputId == HwihaCourtExpansionInput.ABANDON_COUNTY) {
                        if (nation.capitalCityId == county.id) return fail(HwihaLegacyCourtFailure.CAPITAL_REQUIRED)
                        if (state.counties.count { it.nationId == nation.id } <= 1) return fail(HwihaLegacyCourtFailure.LAST_COUNTY)
                    }
                    eligible(county = county)
                }
                HwihaLegacyCourtInput.INSTITUTION -> {
                    val source = HwihaPortableStock.read(nation.meta, nation.gold, nation.rice)
                    if (source.money < 100) fail(HwihaLegacyCourtFailure.INSUFFICIENT_STOCK) else eligible(source = source)
                }
                in HwihaCourtResourceInput.INPUT_IDS -> {
                    val request = HwihaCourtResourceInput.parse(actorId, inputId, raw)!!
                    val delta = with(HwihaTransferRules) { request.resource.amount(request.amount.toLong()) }
                    val person = if (inputId == HwihaCourtResourceInput.CONFISCATE)
                        state.person(request.targetId)?.takeIf { it.nationId == nation.id &&
                            state.cards.any { card -> card.masterId == actorId && card.generalId == it.id } }
                        else null
                    val targetNation = if (inputId == HwihaCourtResourceInput.AID)
                        state.nation(request.targetId)?.takeIf { it.id != nation.id } else null
                    if (person == null && targetNation == null) return fail(HwihaLegacyCourtFailure.TARGET_UNAVAILABLE)
                    if (targetNation != null && !hasEnvoy(actorId, targetNation.id, state)) return fail(HwihaLegacyCourtFailure.ENVOY_REQUIRED)
                    val source = if (person != null) HwihaPortableStock.read(person.meta, person.gold, person.rice)
                        else HwihaPortableStock.read(nation.meta, nation.gold, nation.rice)
                    val destination = if (person != null) HwihaPortableStock.read(nation.meta, nation.gold, nation.rice)
                        else HwihaPortableStock.read(targetNation!!.meta, targetNation.gold, targetNation.rice)
                    if (source.debit(delta) == null) return fail(HwihaLegacyCourtFailure.INSUFFICIENT_STOCK)
                    val received = destination.credit(delta)
                    if (received.money > Int.MAX_VALUE || received.grain > Int.MAX_VALUE)
                        return fail(HwihaLegacyCourtFailure.STOCK_OVERFLOW)
                    eligible(person = person, targetNation = targetNation, source = source, destination = destination)
                }
                in HwihaDiplomacyInput.INPUT_IDS -> {
                    val request = HwihaDiplomacyInput.parse(actorId, inputId, raw)!!
                    val target = state.nation(request.targetNationId)?.takeIf { it.id != nation.id }
                        ?: return fail(HwihaLegacyCourtFailure.TARGET_UNAVAILABLE)
                    if (!hasEnvoy(actorId, target.id, state)) return fail(HwihaLegacyCourtFailure.ENVOY_REQUIRED)
                    val current = state.diplomacy.singleOrNull { it.fromNationId == nation.id && it.toNationId == target.id }
                        ?: return fail(HwihaLegacyCourtFailure.STATE_UNAVAILABLE)
                    when (inputId) {
                        HwihaDiplomacyInput.DECLARE_WAR -> if (current.state == DiplomacyState.WAR ||
                            current.state == DiplomacyState.DECLARATION) return fail(HwihaLegacyCourtFailure.ALREADY_AT_WAR)
                        HwihaDiplomacyInput.OFFER_PEACE -> if (current.state !in setOf(
                            DiplomacyState.WAR, DiplomacyState.DECLARATION)) return fail(HwihaLegacyCourtFailure.NOT_AT_WAR)
                        HwihaDiplomacyInput.BREAK_NON_AGGRESSION -> if (current.state != DiplomacyState.NON_AGGRESSION)
                            return fail(HwihaLegacyCourtFailure.AGREEMENT_UNAVAILABLE)
                    }
                    eligible(targetNation = target)
                }
                else -> fail(HwihaLegacyCourtFailure.INVALID_INPUT)
            }
        } catch (_: IllegalArgumentException) { fail(HwihaLegacyCourtFailure.STATE_UNAVAILABLE) }
          catch (_: ArithmeticException) { fail(HwihaLegacyCourtFailure.STOCK_OVERFLOW) }
    }

    private fun hasEnvoy(actorId: Int, targetNationId: Int, state: HwihaDomesticProjection): Boolean =
        state.cards.any { card -> card.masterId == actorId && card.generalId?.let(state::person)?.let { person ->
            val placement = HwihaPlacementState.read(person.meta)?.active
            placement?.order?.post == PlacementPost.ENVOY &&
                placement.order.target == PlacementTarget.Nation(targetNationId) && placement.arrivedAt != null
        } == true }
}
