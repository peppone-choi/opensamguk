package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCard
import opensamguk.logic.domestic.DomesticProjection

enum class RetireFailure(val message: String) {
    WRONG_RULE_PROFILE("이 월드에서는 은퇴할 수 없습니다."),
    INVALID_INPUT("승계할 인물을 한 명 지정해 주세요."),
    ACTOR_NOT_FOUND("은퇴할 장수를 찾을 수 없습니다."),
    ALREADY_RETIRED("이미 은퇴한 장수입니다."),
    BATTLE_PENDING("조우 처리가 끝나야 은퇴할 수 있습니다."),
    SUCCESSOR_UNAVAILABLE("지정한 승계 후보를 찾을 수 없습니다."),
    SUCCESSOR_NOT_RETAINER("직접 거느린 인물만 승계 후보로 지정할 수 있습니다."),
    SUCCESSOR_UNAVAILABLE_FOR_CONTROL("다른 플레이어의 장수나 활동할 수 없는 인물에게 넘길 수 없습니다."),
    RETAINER_NAME_CONFLICT("승계 뒤 같은 주인에게 같은 이름의 인물 카드가 생깁니다."),
    STATE_UNAVAILABLE("승계에 필요한 상태를 확인할 수 없습니다."),
    ALREADY_PROCESSED("이 순에는 이미 은퇴를 처리했습니다."),
}

sealed interface RetireAssessment {
    data class Eligible(val actor: DomesticPerson, val successor: DomesticPerson,
        val successorCard: DomesticCard, val wasLord: Boolean) : RetireAssessment
    data class Rejected(val reason: RetireFailure) : RetireAssessment
}

/** The same successor gate runs at reservation and immediately before the political action. */
object RetireRules {
    fun assess(request: RetireRequest, state: DomesticProjection): RetireAssessment {
        fun reject(reason: RetireFailure) = RetireAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return reject(RetireFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || request.successorGeneralId <= 0 || request.actorId == request.successorGeneralId)
            return reject(RetireFailure.INVALID_INPUT)
        val actor = state.person(request.actorId) ?: return reject(RetireFailure.ACTOR_NOT_FOUND)
        if (actor.meta["retired"] == true || actor.npcState == 5) return reject(RetireFailure.ALREADY_RETIRED)
        if (actor.inBattle) return reject(RetireFailure.BATTLE_PENDING)
        val successor = state.person(request.successorGeneralId) ?: return reject(RetireFailure.SUCCESSOR_UNAVAILABLE)
        val card = state.cards.singleOrNull { it.masterId == actor.id && it.generalId == successor.id }
            ?: return reject(RetireFailure.SUCCESSOR_NOT_RETAINER)
        if (successor.nationId != actor.nationId || successor.userOwned || successor.npcState == 5 || successor.inBattle)
            return reject(RetireFailure.SUCCESSOR_UNAVAILABLE_FOR_CONTROL)
        val outerCards = state.cards.filter { it.generalId == actor.id }
        if (outerCards.size > 1 || outerCards.any { it.masterId == successor.id })
            return reject(RetireFailure.STATE_UNAVAILABLE)
        // The DB enforces (world_id, master_general_id, name). A collision discovered only at
        // flush would roll back the entire tick, including unrelated generals' turns.
        val inheritedNames = state.cards.asSequence()
            .filter { it.masterId == actor.id && it.id != card.id }
            .map { it.name ?: it.generalId?.let { id -> state.person(id)?.name } }
            .toList()
        val successorNames = state.cards.asSequence().filter { it.masterId == successor.id }
            .map { it.name ?: it.generalId?.let { id -> state.person(id)?.name } }.toList()
        if (inheritedNames.any { it.isNullOrBlank() } || successorNames.any { it.isNullOrBlank() })
            return reject(RetireFailure.STATE_UNAVAILABLE)
        if ((inheritedNames + successorNames).distinct().size != inheritedNames.size + successorNames.size)
            return reject(RetireFailure.RETAINER_NAME_CONFLICT)
        if (outerCards.any { outer -> state.cards.any {
                it.masterId == outer.masterId && it.id != outer.id && it.name == successor.name
            } }) return reject(RetireFailure.RETAINER_NAME_CONFLICT)
        val wasLord = try { LordStatus.read(actor.meta) }
            catch (_: IllegalArgumentException) { return reject(RetireFailure.STATE_UNAVAILABLE) }
        if (wasLord && (actor.nationId <= 0 || state.nation(actor.nationId) == null))
            return reject(RetireFailure.STATE_UNAVAILABLE)
        return RetireAssessment.Eligible(actor, successor, card, wasLord)
    }
}
