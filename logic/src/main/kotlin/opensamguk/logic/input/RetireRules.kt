package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCard
import opensamguk.logic.domestic.DomesticProjection

enum class HwihaRetireFailure(val message: String) {
    WRONG_RULE_PROFILE("이 월드에서는 은퇴할 수 없습니다."),
    INVALID_INPUT("승계할 인물을 한 명 지정해 주세요."),
    ACTOR_NOT_FOUND("은퇴할 장수를 찾을 수 없습니다."),
    ALREADY_RETIRED("이미 은퇴한 장수입니다."),
    BATTLE_PENDING("조우 처리가 끝나야 은퇴할 수 있습니다."),
    SUCCESSOR_UNAVAILABLE("지정한 승계 후보를 찾을 수 없습니다."),
    SUCCESSOR_NOT_RETAINER("직접 거느린 인물만 승계 후보로 지정할 수 있습니다."),
    SUCCESSOR_UNAVAILABLE_FOR_CONTROL("다른 플레이어의 장수나 활동할 수 없는 인물에게 넘길 수 없습니다."),
    STATE_UNAVAILABLE("승계에 필요한 상태를 확인할 수 없습니다."),
    ALREADY_PROCESSED("이 순에는 이미 은퇴를 처리했습니다."),
}

sealed interface HwihaRetireAssessment {
    data class Eligible(val actor: DomesticPerson, val successor: DomesticPerson,
        val successorCard: DomesticCard, val wasLord: Boolean) : HwihaRetireAssessment
    data class Rejected(val reason: HwihaRetireFailure) : HwihaRetireAssessment
}

/** The same successor gate runs at reservation and immediately before the political action. */
object HwihaRetireRules {
    fun assess(request: HwihaRetireRequest, state: DomesticProjection): HwihaRetireAssessment {
        fun reject(reason: HwihaRetireFailure) = HwihaRetireAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return reject(HwihaRetireFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || request.successorGeneralId <= 0 || request.actorId == request.successorGeneralId)
            return reject(HwihaRetireFailure.INVALID_INPUT)
        val actor = state.person(request.actorId) ?: return reject(HwihaRetireFailure.ACTOR_NOT_FOUND)
        if (actor.meta["hwihaRetired"] == true || actor.npcState == 5) return reject(HwihaRetireFailure.ALREADY_RETIRED)
        if (actor.inBattle) return reject(HwihaRetireFailure.BATTLE_PENDING)
        val successor = state.person(request.successorGeneralId) ?: return reject(HwihaRetireFailure.SUCCESSOR_UNAVAILABLE)
        val card = state.cards.singleOrNull { it.masterId == actor.id && it.generalId == successor.id }
            ?: return reject(HwihaRetireFailure.SUCCESSOR_NOT_RETAINER)
        if (successor.nationId != actor.nationId || successor.userOwned || successor.npcState == 5 || successor.inBattle)
            return reject(HwihaRetireFailure.SUCCESSOR_UNAVAILABLE_FOR_CONTROL)
        val wasLord = try { HwihaLordStatus.read(actor.meta) }
            catch (_: IllegalArgumentException) { return reject(HwihaRetireFailure.STATE_UNAVAILABLE) }
        if (wasLord && (actor.nationId <= 0 || state.nation(actor.nationId) == null))
            return reject(HwihaRetireFailure.STATE_UNAVAILABLE)
        return HwihaRetireAssessment.Eligible(actor, successor, card, wasLord)
    }
}
