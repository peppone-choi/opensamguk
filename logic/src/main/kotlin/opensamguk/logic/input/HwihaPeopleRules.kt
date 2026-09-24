package opensamguk.logic.input

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
    CAPACITY_UNAVAILABLE("인물 카드 수용 여력이 없습니다."),
    DUPLICATE_RETAINER_NAME("같은 이름의 인물 카드가 이미 휘하에 있습니다."),
    INSUFFICIENT_STOCK("행동 비용을 낼 수 없습니다."),
    ALREADY_PROCESSED("이 순에는 이미 인물 행동을 실행했습니다."),
}

sealed interface HwihaPeopleAssessment {
    data class Eligible(val actor: DomesticPerson, val county: DomesticCounty?,
        val candidateIds: List<Int> = emptyList(), val target: DomesticPerson? = null) : HwihaPeopleAssessment
    data class Rejected(val reason: HwihaPeopleFailure) : HwihaPeopleAssessment
}

/** One location and target gate shared by reservation, options and immediate turn recheck. */
object HwihaPeopleRules {
    fun assess(request: HwihaPeopleRequest, state: HwihaDomesticProjection): HwihaPeopleAssessment {
        fun reject(reason: HwihaPeopleFailure) = HwihaPeopleAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return reject(HwihaPeopleFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || request.inputId !in HwihaPeopleInput.INPUT_IDS) return reject(HwihaPeopleFailure.INVALID_INPUT)
        val actor = state.person(request.actorId) ?: return reject(HwihaPeopleFailure.ACTOR_NOT_FOUND)
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
        fun free(target: DomesticPerson): Boolean = target.id != actor.id && target.nationId == 0 &&
            !target.userOwned && target.npcState >= 2 && state.cards.none { it.generalId == target.id } &&
            "hwihaCaptive" !in target.meta
        fun capacityFor(target: DomesticPerson): HwihaPeopleFailure? {
            val owned = state.cards.filter { it.masterId == actor.id }
            if (owned.any { card -> state.person(card.generalId ?: return HwihaPeopleFailure.CAPACITY_UNAVAILABLE)?.name == target.name })
                return HwihaPeopleFailure.DUPLICATE_RETAINER_NAME
            return try {
                val maximum = HwihaPersonPolicyState.read(actor.meta)?.renownCapacity
                    ?: return HwihaPeopleFailure.CAPACITY_UNAVAILABLE
                val occupied = owned.sumOf { card ->
                    val person = state.person(card.generalId ?: return HwihaPeopleFailure.CAPACITY_UNAVAILABLE)
                        ?: return HwihaPeopleFailure.CAPACITY_UNAVAILABLE
                    HwihaRenownRules.personCost(person.leadership, person.strength, person.intelligence,
                        person.politics, person.charm).toLong()
                }
                val next = occupied + HwihaRenownRules.personCost(target.leadership, target.strength,
                    target.intelligence, target.politics, target.charm)
                if (next > maximum) HwihaPeopleFailure.CAPACITY_UNAVAILABLE else null
            } catch (_: IllegalArgumentException) { HwihaPeopleFailure.CAPACITY_UNAVAILABLE }
        }
        return when (request.inputId) {
            HwihaPeopleInput.SEARCH -> {
                if (request.targetGeneralId != null) return reject(HwihaPeopleFailure.INVALID_INPUT)
                val candidates = state.peopleAt(node).filter(::free).map { it.id }.filter { it !in known }.sorted()
                if (candidates.isEmpty()) reject(HwihaPeopleFailure.NO_CANDIDATE)
                else HwihaPeopleAssessment.Eligible(actor, county, candidateIds = candidates)
            }
            HwihaPeopleInput.EMPLOY -> {
                val target = request.targetGeneralId?.let(state::person) ?: return reject(HwihaPeopleFailure.TARGET_UNAVAILABLE)
                if (target.node != node) return reject(HwihaPeopleFailure.TARGET_UNAVAILABLE)
                if (!free(target)) return reject(HwihaPeopleFailure.TARGET_NOT_FREE)
                if (target.id !in known) return reject(HwihaPeopleFailure.TARGET_NOT_DISCOVERED)
                capacityFor(target)?.let { return reject(it) }
                HwihaPeopleAssessment.Eligible(actor, county, target = target)
            }
            HwihaPeopleInput.PERSUADE_CAPTIVE -> {
                val target = request.targetGeneralId?.let(state::person) ?: return reject(HwihaPeopleFailure.TARGET_UNAVAILABLE)
                if (target.node != node) return reject(HwihaPeopleFailure.TARGET_UNAVAILABLE)
                val marker = target.meta["hwihaCaptive"] as? Map<*, *>
                if (marker?.get("captorGeneralId") != actor.id ||
                    state.cards.any { it.generalId == target.id && it.masterId == actor.id } ||
                    state.cards.count { it.generalId == target.id } > 1)
                    return reject(HwihaPeopleFailure.TARGET_NOT_CAPTIVE)
                capacityFor(target)?.let { return reject(it) }
                HwihaPeopleAssessment.Eligible(actor, county, target = target)
            }
            else -> reject(HwihaPeopleFailure.INVALID_INPUT)
        }
    }
}
