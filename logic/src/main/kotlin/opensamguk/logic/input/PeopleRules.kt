package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticProjection

import opensamguk.logic.retainer.RetainerRules

enum class PeopleFailure(val message: String) {
    WRONG_RULE_PROFILE("이 세계에서는 인물 행동을 사용할 수 없습니다."),
    INVALID_INPUT("인물 행동 인자를 확인할 수 없습니다."),
    ACTOR_NOT_FOUND("행동할 장수를 찾을 수 없습니다."),
    POSITION_UNAVAILABLE("장수의 현재 육상 위치를 확인할 수 없습니다."),
    BATTLE_PENDING("조우 처리가 끝나야 인물 행동을 할 수 있습니다."),
    COUNTY_UNAVAILABLE("현재 위치에 행정 縣이 없습니다."),
    STATE_UNAVAILABLE("현재 인물 상태를 확인할 수 없습니다."),
    NO_CANDIDATE("현재 縣에서 새로 탐방할 재야 인물이 없습니다."),
    TARGET_UNAVAILABLE("대상 인물을 현재 縣에서 만날 수 없습니다."),
    TARGET_NOT_DISCOVERED("먼저 현재 縣에서 인재를 탐색해야 합니다."),
    TARGET_NOT_FREE("대상은 재야 인물이 아닙니다."),
    TARGET_NOT_CAPTIVE("대상은 본인이 잡은 포로가 아닙니다."),
    TARGET_IS_LORD("다른 세력의 주공 포로는 국가 지위 처리 전까지 설득할 수 없습니다."),
    CAPACITY_UNAVAILABLE("인물 카드 수용 여력이 없습니다."),
    DUPLICATE_RETAINER_NAME("같은 이름의 인물 카드가 이미 휘하에 있습니다."),
    ALREADY_PROCESSED("이 순에는 이미 인물 행동을 실행했습니다."),
}

sealed interface PeopleAssessment {
    data class Eligible(val actor: DomesticPerson, val county: DomesticCounty?,
        val candidateIds: List<Int> = emptyList(), val target: DomesticPerson? = null,
        val joiningGeneralIds: List<Int> = emptyList()) : PeopleAssessment
    data class Rejected(val reason: PeopleFailure) : PeopleAssessment
}

/** One location and target gate shared by reservation, options and immediate turn recheck. */
object PeopleRules {
    fun assess(request: PeopleRequest, state: DomesticProjection): PeopleAssessment {
        fun reject(reason: PeopleFailure) = PeopleAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return reject(PeopleFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || request.inputId !in PeopleInput.INPUT_IDS) return reject(PeopleFailure.INVALID_INPUT)
        val actor = state.person(request.actorId) ?: return reject(PeopleFailure.ACTOR_NOT_FOUND)
        if (actor.nationId <= 0) return reject(PeopleFailure.STATE_UNAVAILABLE)
        if (actor.inBattle) return reject(PeopleFailure.BATTLE_PENDING)
        val node = actor.node ?: return reject(PeopleFailure.POSITION_UNAVAILABLE)
        if (state.landProvinceIds?.contains(node) != true) return reject(PeopleFailure.STATE_UNAVAILABLE)
        val counties = state.counties.filter { it.provinceId == node }
        if (counties.size > 1) return reject(PeopleFailure.STATE_UNAVAILABLE)
        val county = counties.singleOrNull()
        if (county == null && request.inputId != PeopleInput.PERSUADE_CAPTIVE)
            return reject(PeopleFailure.COUNTY_UNAVAILABLE)
        val known = try { TalentDiscovery.read(actor.meta) }
            catch (_: IllegalArgumentException) { return reject(PeopleFailure.STATE_UNAVAILABLE) }
        fun free(target: DomesticPerson): Boolean = target.nationId == 0 &&
            RetainerRules.existingCandidateEligible(actor.id, actor.nationId, target.id, target.nationId,
                target.npcState, if (target.userOwned) "1" else null, target.officerLevel,
                state.cards.any { it.generalId == target.id }) &&
            try { !LordStatus.read(target.meta) }
            catch (_: IllegalArgumentException) { false }
        fun capacityFor(target: DomesticPerson): PeopleFailure? {
            val owned = state.cards.filter { it.masterId == actor.id }
            val incomingName = state.cards.singleOrNull { it.generalId == target.id }?.name ?: target.name
            if (owned.any { card -> (card.name ?: card.generalId?.let(state::person)?.name) == incomingName })
                return PeopleFailure.DUPLICATE_RETAINER_NAME
            val budget = EnlistmentBudget.assess(target.id, state.profile, state.people.map { person ->
                PersonPolicyInput(person.id, person.nationId, person.leadership, person.strength,
                    person.intelligence, person.politics, person.charm, person.meta)
            }, state.cards.map { DirectPersonCard(it.id, it.masterId, it.generalId) })
            val ready = budget as? RenownBudgetResult.Ready ?: return PeopleFailure.CAPACITY_UNAVAILABLE
            if (actor.id in ready.unavailableOwnerReasons) return PeopleFailure.CAPACITY_UNAVAILABLE
            val freeRenown = ready.freeRenownByOwner[actor.id] ?: try {
                if (owned.isNotEmpty()) return PeopleFailure.CAPACITY_UNAVAILABLE
                PersonPolicyState.read(actor.meta)?.renownCapacity
                    ?: return PeopleFailure.CAPACITY_UNAVAILABLE
            } catch (_: IllegalArgumentException) { return PeopleFailure.CAPACITY_UNAVAILABLE }
            return if (freeRenown <
                ready.actorCardCost) PeopleFailure.CAPACITY_UNAVAILABLE else null
        }
        fun joiningFor(target: DomesticPerson): List<Int>? {
            val children = state.cards.filter { it.generalId != null }.groupBy({ it.masterId }, { it.generalId!! })
            val queue = ArrayDeque<Int>().apply { add(target.id) }
            val seen = linkedSetOf<Int>()
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!seen.add(id) || id == actor.id) return null
                val person = state.person(id) ?: return null
                if (person.nationId != target.nationId || (id != target.id && (person.userOwned || person.npcState != 2)))
                    return null
                val lord = try { LordStatus.read(person.meta) } catch (_: IllegalArgumentException) { return null }
                if (id != target.id && lord) return null
                val policy = try { PersonPolicyState.read(person.meta) } catch (_: IllegalArgumentException) { return null }
                if (policy == null) return null
                queue.addAll(children[id].orEmpty())
            }
            return seen.toList()
        }
        return when (request.inputId) {
            PeopleInput.SEARCH -> {
                if (request.targetGeneralId != null) return reject(PeopleFailure.INVALID_INPUT)
                val candidates = state.peopleAt(node).filter(::free).map { it.id }.filter { it !in known }.sorted()
                if (candidates.isEmpty()) reject(PeopleFailure.NO_CANDIDATE)
                else PeopleAssessment.Eligible(actor, county, candidateIds = candidates)
            }
            PeopleInput.EMPLOY -> {
                val targetId = request.targetGeneralId ?: return reject(PeopleFailure.INVALID_INPUT)
                if (targetId !in known) return reject(PeopleFailure.TARGET_NOT_DISCOVERED)
                val target = state.person(targetId) ?: return reject(PeopleFailure.TARGET_UNAVAILABLE)
                if (target.node != node) return reject(PeopleFailure.TARGET_UNAVAILABLE)
                if (!free(target)) return reject(PeopleFailure.TARGET_NOT_FREE)
                capacityFor(target)?.let { return reject(it) }
                val joining = joiningFor(target) ?: return reject(PeopleFailure.STATE_UNAVAILABLE)
                PeopleAssessment.Eligible(actor, county, target = target, joiningGeneralIds = joining)
            }
            PeopleInput.PERSUADE_CAPTIVE -> {
                val target = request.targetGeneralId?.let(state::person) ?: return reject(PeopleFailure.TARGET_UNAVAILABLE)
                if (target.userOwned || target.npcState != 2) return reject(PeopleFailure.TARGET_NOT_CAPTIVE)
                if (target.node != node) return reject(PeopleFailure.TARGET_UNAVAILABLE)
                val marker = target.meta["hwihaCaptive"] as? Map<*, *>
                if (marker?.get("captorGeneralId") != actor.id ||
                    state.cards.any { it.generalId == target.id && it.masterId == actor.id } ||
                    state.cards.count { it.generalId == target.id } > 1)
                    return reject(PeopleFailure.TARGET_NOT_CAPTIVE)
                val isLord = try { LordStatus.read(target.meta) }
                    catch (_: IllegalArgumentException) { return reject(PeopleFailure.STATE_UNAVAILABLE) }
                if (target.nationId > 0 && target.nationId != actor.nationId && isLord)
                    return reject(PeopleFailure.TARGET_IS_LORD)
                capacityFor(target)?.let { return reject(it) }
                val joining = joiningFor(target) ?: return reject(PeopleFailure.STATE_UNAVAILABLE)
                PeopleAssessment.Eligible(actor, county, target = target, joiningGeneralIds = joining)
            }
            else -> reject(PeopleFailure.INVALID_INPUT)
        }
    }
}
