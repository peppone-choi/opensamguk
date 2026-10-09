package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCard
import opensamguk.logic.domestic.DomesticProjection

enum class RetireFailure(val message: String) {
    WRONG_RULE_PROFILE("이 월드에서는 은퇴할 수 없습니다."),
    INVALID_INPUT("승계할 인물을 한 명 지정해 주세요."),
    ACTOR_NOT_FOUND("은퇴할 장수를 찾을 수 없습니다."),
    ALREADY_RETIRED("이미 은퇴한 장수입니다."),
    AGE_TOO_YOUNG("60세 이상인 장수만 은퇴할 수 있습니다."),
    BATTLE_PENDING("조우 처리가 끝나야 은퇴할 수 있습니다."),
    SUCCESSOR_UNAVAILABLE("지정한 승계 후보를 찾을 수 없습니다."),
    SUCCESSOR_NOT_RETAINER("직접 거느린 인물만 승계 후보로 지정할 수 있습니다."),
    SUCCESSOR_UNAVAILABLE_FOR_CONTROL("다른 플레이어의 장수나 활동할 수 없는 인물에게 넘길 수 없습니다."),
    SUCCESSOR_RENOWN_EXCEEDED("승계할 인물 카드의 명망 비용이 후계자의 기존 상한을 초과합니다."),
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
        val age = actor.age ?: return reject(RetireFailure.STATE_UNAVAILABLE)
        if (age < 0) return reject(RetireFailure.STATE_UNAVAILABLE)
        if (age < 60) return reject(RetireFailure.AGE_TOO_YOUNG)
        if (actor.inBattle) return reject(RetireFailure.BATTLE_PENDING)
        val successor = state.person(request.successorGeneralId) ?: return reject(RetireFailure.SUCCESSOR_UNAVAILABLE)
        val card = state.cards.singleOrNull { it.masterId == actor.id && it.generalId == successor.id }
            ?: return reject(RetireFailure.SUCCESSOR_NOT_RETAINER)
        if (successor.nationId != actor.nationId || successor.userOwned || successor.npcState != 2 ||
            successor.meta["retired"] == true || successor.inBattle)
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
        if (wasLord) {
            val nation = state.nation(actor.nationId)
            if (actor.nationId <= 0 || nation == null ||
                (nation.chiefGeneralId != null && nation.chiefGeneralId != actor.id))
                return reject(RetireFailure.STATE_UNAVAILABLE)
        }
        successorRenownFailure(actor, successor, card, state)?.let { return reject(it) }
        return RetireAssessment.Eligible(actor, successor, card, wasLord)
    }

    private fun successorRenownFailure(actor: DomesticPerson, successor: DomesticPerson,
        successorCard: DomesticCard, state: DomesticProjection): RetireFailure? {
        // Assess the resulting direct cards without changing the world or inheriting the actor's cap.
        // The successor's own card is consumed; neither that card nor the retired actor occupies renown.
        val cards = state.cards.filter {
            (it.masterId == actor.id || it.masterId == successor.id) && it.id != successorCard.id
        }.map { DirectPersonCard(it.id, successor.id, it.generalId) }
        if (cards.any { it.generalId == successor.id || it.generalId == actor.id })
            return RetireFailure.STATE_UNAVAILABLE
        val relevantIds = cards.mapNotNull { it.generalId }.toSet() + successor.id
        val people = state.people.filter { it.id in relevantIds }.map {
            PersonPolicyInput(it.id, it.nationId, it.leadership, it.strength, it.intelligence,
                it.politics, it.charm, it.meta)
        }
        val budget = EnlistmentBudget.assess(successor.id, state.profile, people, cards)
        if (budget !is RenownBudgetResult.Ready) return RetireFailure.STATE_UNAVAILABLE
        return when (budget.unavailableOwnerReasons[successor.id]) {
            null -> if (successor.id in budget.freeRenownByOwner) null else RetireFailure.STATE_UNAVAILABLE
            RenownBudgetFailure.CAPACITY_EXCEEDED -> RetireFailure.SUCCESSOR_RENOWN_EXCEEDED
            else -> RetireFailure.STATE_UNAVAILABLE
        }
    }
}
