package opensamguk.logic.input

import opensamguk.logic.retainer.RetainerRules

enum class HwihaPeopleFailure(val message: String) {
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

sealed interface HwihaPeopleAssessment {
    data class Eligible(val actor: DomesticPerson, val county: DomesticCounty?,
        val candidateIds: List<Int> = emptyList(), val target: DomesticPerson? = null,
        val joiningGeneralIds: List<Int> = emptyList()) : HwihaPeopleAssessment
    data class Rejected(val reason: HwihaPeopleFailure) : HwihaPeopleAssessment
}

/** One location and target gate shared by reservation, options and immediate turn recheck. */
object HwihaPeopleRules {
    fun assess(request: HwihaPeopleRequest, state: HwihaDomesticProjection): HwihaPeopleAssessment {
        fun reject(reason: HwihaPeopleFailure) = HwihaPeopleAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return reject(HwihaPeopleFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || request.inputId !in HwihaPeopleInput.INPUT_IDS) return reject(HwihaPeopleFailure.INVALID_INPUT)
        val actor = state.person(request.actorId) ?: return reject(HwihaPeopleFailure.ACTOR_NOT_FOUND)
        if (actor.nationId <= 0) return reject(HwihaPeopleFailure.STATE_UNAVAILABLE)
        if (actor.inBattle) return reject(HwihaPeopleFailure.BATTLE_PENDING)
        val node = actor.node ?: return reject(HwihaPeopleFailure.POSITION_UNAVAILABLE)
        if (state.landProvinceIds?.contains(node) != true) return reject(HwihaPeopleFailure.STATE_UNAVAILABLE)
        val counties = state.counties.filter { it.provinceId == node }
        if (counties.size > 1) return reject(HwihaPeopleFailure.STATE_UNAVAILABLE)
        val county = counties.singleOrNull()
        if (county == null && request.inputId != HwihaPeopleInput.PERSUADE_CAPTIVE)
            return reject(HwihaPeopleFailure.COUNTY_UNAVAILABLE)
        val known = try { HwihaTalentDiscovery.read(actor.meta) }
            catch (_: IllegalArgumentException) { return reject(HwihaPeopleFailure.STATE_UNAVAILABLE) }
        fun free(target: DomesticPerson): Boolean = target.nationId == 0 &&
            RetainerRules.existingCandidateEligible(actor.id, actor.nationId, target.id, target.nationId,
                target.npcState, if (target.userOwned) "1" else null, target.officerLevel,
                state.cards.any { it.generalId == target.id }) &&
            try { !HwihaLordStatus.read(target.meta) }
            catch (_: IllegalArgumentException) { false }
        fun capacityFor(target: DomesticPerson): HwihaPeopleFailure? {
            val owned = state.cards.filter { it.masterId == actor.id }
            val incomingName = state.cards.singleOrNull { it.generalId == target.id }?.name ?: target.name
            if (owned.any { card -> (card.name ?: card.generalId?.let(state::person)?.name) == incomingName })
                return HwihaPeopleFailure.DUPLICATE_RETAINER_NAME
            val budget = HwihaEnlistmentBudget.assess(target.id, state.profile, state.people.map { person ->
                PersonPolicyInput(person.id, person.nationId, person.leadership, person.strength,
                    person.intelligence, person.politics, person.charm, person.meta)
            }, state.cards.map { DirectPersonCard(it.id, it.masterId, it.generalId) })
            val ready = budget as? RenownBudgetResult.Ready ?: return HwihaPeopleFailure.CAPACITY_UNAVAILABLE
            if (actor.id in ready.unavailableOwnerReasons) return HwihaPeopleFailure.CAPACITY_UNAVAILABLE
            val freeRenown = ready.freeRenownByOwner[actor.id] ?: try {
                if (owned.isNotEmpty()) return HwihaPeopleFailure.CAPACITY_UNAVAILABLE
                HwihaPersonPolicyState.read(actor.meta)?.renownCapacity
                    ?: return HwihaPeopleFailure.CAPACITY_UNAVAILABLE
            } catch (_: IllegalArgumentException) { return HwihaPeopleFailure.CAPACITY_UNAVAILABLE }
            return if (freeRenown <
                ready.actorCardCost) HwihaPeopleFailure.CAPACITY_UNAVAILABLE else null
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
                val lord = try { HwihaLordStatus.read(person.meta) } catch (_: IllegalArgumentException) { return null }
                if (id != target.id && lord) return null
                val policy = try { HwihaPersonPolicyState.read(person.meta) } catch (_: IllegalArgumentException) { return null }
                if (policy == null) return null
                queue.addAll(children[id].orEmpty())
            }
            return seen.toList()
        }
        return when (request.inputId) {
            HwihaPeopleInput.SEARCH -> {
                if (request.targetGeneralId != null) return reject(HwihaPeopleFailure.INVALID_INPUT)
                val candidates = state.peopleAt(node).filter(::free).map { it.id }.filter { it !in known }.sorted()
                if (candidates.isEmpty()) reject(HwihaPeopleFailure.NO_CANDIDATE)
                else HwihaPeopleAssessment.Eligible(actor, county, candidateIds = candidates)
            }
            HwihaPeopleInput.EMPLOY -> {
                val targetId = request.targetGeneralId ?: return reject(HwihaPeopleFailure.INVALID_INPUT)
                if (targetId !in known) return reject(HwihaPeopleFailure.TARGET_NOT_DISCOVERED)
                val target = state.person(targetId) ?: return reject(HwihaPeopleFailure.TARGET_UNAVAILABLE)
                if (target.node != node) return reject(HwihaPeopleFailure.TARGET_UNAVAILABLE)
                if (!free(target)) return reject(HwihaPeopleFailure.TARGET_NOT_FREE)
                capacityFor(target)?.let { return reject(it) }
                val joining = joiningFor(target) ?: return reject(HwihaPeopleFailure.STATE_UNAVAILABLE)
                HwihaPeopleAssessment.Eligible(actor, county, target = target, joiningGeneralIds = joining)
            }
            HwihaPeopleInput.PERSUADE_CAPTIVE -> {
                val target = request.targetGeneralId?.let(state::person) ?: return reject(HwihaPeopleFailure.TARGET_UNAVAILABLE)
                if (target.userOwned || target.npcState != 2) return reject(HwihaPeopleFailure.TARGET_NOT_CAPTIVE)
                if (target.node != node) return reject(HwihaPeopleFailure.TARGET_UNAVAILABLE)
                val marker = target.meta["hwihaCaptive"] as? Map<*, *>
                if (marker?.get("captorGeneralId") != actor.id ||
                    state.cards.any { it.generalId == target.id && it.masterId == actor.id } ||
                    state.cards.count { it.generalId == target.id } > 1)
                    return reject(HwihaPeopleFailure.TARGET_NOT_CAPTIVE)
                val isLord = try { HwihaLordStatus.read(target.meta) }
                    catch (_: IllegalArgumentException) { return reject(HwihaPeopleFailure.STATE_UNAVAILABLE) }
                if (target.nationId > 0 && target.nationId != actor.nationId && isLord)
                    return reject(HwihaPeopleFailure.TARGET_IS_LORD)
                capacityFor(target)?.let { return reject(it) }
                val joining = joiningFor(target) ?: return reject(HwihaPeopleFailure.STATE_UNAVAILABLE)
                HwihaPeopleAssessment.Eligible(actor, county, target = target, joiningGeneralIds = joining)
            }
            else -> reject(HwihaPeopleFailure.INVALID_INPUT)
        }
    }
}
