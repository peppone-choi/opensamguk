package opensamguk.logic.input

import opensamguk.logic.domestic.PlacementState

import opensamguk.logic.domestic.PlacementPost
import opensamguk.logic.domestic.PlacementTarget

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.domestic.DomesticRules

import opensamguk.logic.economy.Resources
import opensamguk.logic.diplomacy.DiplomacyState

/** Ten legacy court orders use one strict input and one pure admission/execution gate. */
object CourtInput {
    const val INSTITUTION = "court.institution"
    val INPUT_IDS = CourtExpansionInput.INPUT_IDS + CourtResourceInput.INPUT_IDS +
        DiplomacyInput.INPUT_IDS + INSTITUTION

    fun canonical(actorId: Int, inputId: String, raw: String?): String? = when (inputId) {
        in CourtExpansionInput.INPUT_IDS -> CourtExpansionInput.parse(actorId, inputId, raw)
            ?.let(CourtExpansionInput::canonicalJson)
        in CourtResourceInput.INPUT_IDS -> CourtResourceInput.parse(actorId, inputId, raw)
            ?.let(CourtResourceInput::canonicalJson)
        in DiplomacyInput.INPUT_IDS -> DiplomacyInput.parse(actorId, inputId, raw)
            ?.let(DiplomacyInput::canonicalJson)
        INSTITUTION -> if (raw != null && runCatching { FlatArguments(raw).read().isEmpty() }.getOrDefault(false)) "{}" else null
        else -> null
    }
}

enum class CourtFailure(val message: String) {
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

data class CourtReady(val actor: DomesticPerson, val nation: DomesticNation,
    val county: DomesticCounty? = null, val targetPerson: DomesticPerson? = null,
    val targetNation: DomesticNation? = null, val corps: DeployedCorps? = null,
    val sourceStock: Resources? = null, val destinationStock: Resources? = null)
sealed interface CourtAssessment {
    data class Eligible(val ready: CourtReady) : CourtAssessment
    data class Rejected(val reason: CourtFailure) : CourtAssessment
}

object CourtRules {
    fun assess(actorId: Int, inputId: String, raw: String, state: DomesticProjection): CourtAssessment {
        fun fail(reason: CourtFailure) = CourtAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return fail(CourtFailure.WRONG_RULE_PROFILE)
        if (CourtInput.canonical(actorId, inputId, raw) == null) return fail(CourtFailure.INVALID_INPUT)
        val actor = state.person(actorId) ?: return fail(CourtFailure.ACTOR_NOT_FOUND)
        val nation = state.nation(actor.nationId) ?: return fail(CourtFailure.NOT_RULER)
        if (DomesticRules.rulerOf(nation.id, state)?.id != actorId) return fail(CourtFailure.NOT_RULER)
        fun eligible(county: DomesticCounty? = null, person: DomesticPerson? = null,
            targetNation: DomesticNation? = null, corps: DeployedCorps? = null,
            source: Resources? = null, destination: Resources? = null) =
            CourtAssessment.Eligible(CourtReady(actor, nation, county, person, targetNation, corps, source, destination))
        return try {
            when (inputId) {
                CourtExpansionInput.RELEASE_CORPS -> {
                    val request = CourtExpansionInput.parse(actorId, inputId, raw) as CourtExpansionRequest.ReleaseCorps
                    val person = state.person(request.targetGeneralId)?.takeIf { it.nationId == nation.id }
                        ?: return fail(CourtFailure.TARGET_UNAVAILABLE)
                    val corps = DomesticRules.deployedCorps(state).singleOrNull {
                        it.commanderGeneralId == person.id && it.ownerGeneralId == actorId
                    } ?: return fail(CourtFailure.CORPS_UNAVAILABLE)
                    eligible(person = person, corps = corps)
                }
                CourtExpansionInput.ABANDON_COUNTY, CourtExpansionInput.MOVE_CAPITAL -> {
                    val request = CourtExpansionInput.parse(actorId, inputId, raw) as CourtExpansionRequest.County
                    val county = state.county(request.countyId)?.takeIf { it.nationId == nation.id }
                        ?: return fail(CourtFailure.COUNTY_UNAVAILABLE)
                    if (inputId == CourtExpansionInput.ABANDON_COUNTY) {
                        if (nation.capitalCityId == county.id) return fail(CourtFailure.CAPITAL_REQUIRED)
                        if (state.counties.count { it.nationId == nation.id } <= 1) return fail(CourtFailure.LAST_COUNTY)
                    }
                    eligible(county = county)
                }
                CourtInput.INSTITUTION -> {
                    val source = PortableStock.read(nation.meta, nation.gold, nation.rice)
                    if (source.money < 100) fail(CourtFailure.INSUFFICIENT_STOCK) else eligible(source = source)
                }
                in CourtResourceInput.INPUT_IDS -> {
                    val request = CourtResourceInput.parse(actorId, inputId, raw)!!
                    val delta = with(TransferRules) { request.resource.amount(request.amount.toLong()) }
                    val person = if (inputId == CourtResourceInput.CONFISCATE)
                        state.person(request.targetId)?.takeIf { it.nationId == nation.id &&
                            state.cards.any { card -> card.masterId == actorId && card.generalId == it.id } }
                        else null
                    val targetNation = if (inputId == CourtResourceInput.AID)
                        state.nation(request.targetId)?.takeIf { it.id != nation.id } else null
                    if (person == null && targetNation == null) return fail(CourtFailure.TARGET_UNAVAILABLE)
                    if (targetNation != null && !hasEnvoy(actorId, targetNation.id, state)) return fail(CourtFailure.ENVOY_REQUIRED)
                    val source = if (person != null) PortableStock.read(person.meta, person.gold, person.rice)
                        else PortableStock.read(nation.meta, nation.gold, nation.rice)
                    val destination = if (person != null) PortableStock.read(nation.meta, nation.gold, nation.rice)
                        else PortableStock.read(targetNation!!.meta, targetNation.gold, targetNation.rice)
                    if (source.debit(delta) == null) return fail(CourtFailure.INSUFFICIENT_STOCK)
                    val received = destination.credit(delta)
                    if (received.money > Int.MAX_VALUE || received.grain > Int.MAX_VALUE)
                        return fail(CourtFailure.STOCK_OVERFLOW)
                    eligible(person = person, targetNation = targetNation, source = source, destination = destination)
                }
                in DiplomacyInput.INPUT_IDS -> {
                    val request = DiplomacyInput.parse(actorId, inputId, raw)!!
                    val target = state.nation(request.targetNationId)?.takeIf { it.id != nation.id }
                        ?: return fail(CourtFailure.TARGET_UNAVAILABLE)
                    if (!hasEnvoy(actorId, target.id, state)) return fail(CourtFailure.ENVOY_REQUIRED)
                    val current = state.diplomacy.singleOrNull { it.fromNationId == nation.id && it.toNationId == target.id }
                        ?: return fail(CourtFailure.STATE_UNAVAILABLE)
                    when (inputId) {
                        DiplomacyInput.DECLARE_WAR -> if (current.state == DiplomacyState.WAR ||
                            current.state == DiplomacyState.DECLARATION) return fail(CourtFailure.ALREADY_AT_WAR)
                        DiplomacyInput.OFFER_PEACE -> if (current.state !in setOf(
                            DiplomacyState.WAR, DiplomacyState.DECLARATION)) return fail(CourtFailure.NOT_AT_WAR)
                        DiplomacyInput.BREAK_NON_AGGRESSION -> if (current.state != DiplomacyState.NON_AGGRESSION)
                            return fail(CourtFailure.AGREEMENT_UNAVAILABLE)
                    }
                    eligible(targetNation = target)
                }
                else -> fail(CourtFailure.INVALID_INPUT)
            }
        } catch (_: IllegalArgumentException) { fail(CourtFailure.STATE_UNAVAILABLE) }
          catch (_: ArithmeticException) { fail(CourtFailure.STOCK_OVERFLOW) }
    }

    private fun hasEnvoy(actorId: Int, targetNationId: Int, state: DomesticProjection): Boolean =
        state.cards.any { card -> card.masterId == actorId && card.generalId?.let(state::person)?.let { person ->
            val placement = PlacementState.read(person.meta)?.active
            placement?.order?.post == PlacementPost.ENVOY &&
                placement.order.target == PlacementTarget.Nation(targetNationId) && placement.arrivedAt != null
        } == true }
}
