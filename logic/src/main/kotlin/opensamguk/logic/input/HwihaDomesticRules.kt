package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticDesign
import opensamguk.logic.economy.HwihaCountyWarehouse

/** 장수 한 명의 투영. [node] 는 위치 권위의 현재 육상 省 id(없으면 null), [userOwned] 는 계정 소유(사람 장수) 여부다. */
data class DomesticPerson(
    val id: Int,
    val name: String,
    val nationId: Int,
    val userOwned: Boolean,
    val npcState: Int,
    val officerLevel: Int,
    val leadership: Int,
    val strength: Int,
    val intelligence: Int,
    val politics: Int,
    val charm: Int,
    val node: String?,
    val inBattle: Boolean,
    val meta: Map<String, Any?>,
    val injury: Int = 0,
    val gold: Int = 0,
    val rice: Int = 0,
) {
    fun stat(stat: DomesticDesign.Stat): Int = when (stat) {
        DomesticDesign.Stat.LEADERSHIP -> leadership
        DomesticDesign.Stat.STRENGTH -> strength
        DomesticDesign.Stat.INTELLIGENCE -> intelligence
        DomesticDesign.Stat.POLITICS -> politics
        DomesticDesign.Stat.CHARM -> charm
    }
}

data class DomesticCard(val id: Int, val masterId: Int, val generalId: Int?, val relation: String,
    val name: String? = null)

/** 행정 縣治 城만 싣는다. [provinceId]·[commanderyId] 는 부팅 판의 결속·지리에서 온다(없으면 null). */
data class DomesticCounty(val id: Int, val name: String, val nationId: Int, val provinceId: String?, val commanderyId: String?,
    val meta: Map<String, Any?>)

data class DomesticNation(val id: Int, val name: String, val capitalCityId: Int?, val meta: Map<String, Any?>,
    val level: Int = 0, val gold: Int = 0, val rice: Int = 0, val tech: Double = 0.0,
    val chiefGeneralId: Int? = null)
data class DomesticBugok(val id: Int, val masterGeneralId: Int, val crewTypeId: Int, val training: Int)
data class DomesticDiplomacy(val fromNationId: Int, val toNationId: Int, val state: Int, val term: Int)

/** API 와 엔진이 같은 규칙을 쓰도록 공유하는 투영. [landProvinceIds] 가 null 이면 지도 핀을 확인하지 못한 것이다. */
data class HwihaDomesticProjection(
    val profile: RuleProfile,
    val now: HwihaPhase,
    val people: List<DomesticPerson>,
    val cards: List<DomesticCard>,
    val counties: List<DomesticCounty>,
    val nations: List<DomesticNation>,
    val landProvinceIds: Set<String>?,
    /** 원장이 있을 때만 향당 보너스를 판정한다. 장수 id → 본관 縣治 城 id. */
    val homeCountyByGeneral: Map<Int, Int> = emptyMap(),
    val bugoks: List<DomesticBugok> = emptyList(),
    val countyAdjacency: Map<Int, Set<Int>> = emptyMap(),
    val supportedCrewTypeIds: Set<Int> = emptySet(),
    val diplomacy: List<DomesticDiplomacy> = emptyList(),
    val activeSiegeCountyIds: Set<Int> = emptySet(),
) {
    private val peopleById = people.associateBy { it.id }
    private val countyById = counties.associateBy { it.id }
    private val peopleByNode: Map<String?, List<DomesticPerson>> by lazy { people.sortedBy { it.id }.groupBy { it.node } }
    fun person(id: Int): DomesticPerson? = peopleById[id]
    /** 그 省에 선 장수들(id 순). */
    fun peopleAt(node: String): List<DomesticPerson> = peopleByNode[node].orEmpty()
    fun county(id: Int): DomesticCounty? = countyById[id]
    fun nation(id: Int): DomesticNation? = nations.firstOrNull { it.id == id }
}

enum class DomesticFailure(val message: String) {
    WRONG_RULE_PROFILE("이 월드의 규칙에서 사용할 수 없는 입력입니다."),
    ACTOR_NOT_FOUND("장수를 찾을 수 없습니다."),
    CARD_NOT_FOUND("직접 거느린 인물 카드를 찾을 수 없습니다."),
    CARD_NOT_ON_MAP("지도 위 위치가 없는 카드는 아직 배치할 수 없습니다."),
    HUMAN_CARD("사람 장수는 배치가 아니라 발령으로 자리에 앉힙니다."),
    DIFFERENT_NATION("같은 세력의 카드만 배치할 수 있습니다."),
    CARD_DEPLOYED("출전 중인 지휘 카드는 배치를 바꿀 수 없습니다."),
    CARD_IN_BATTLE("조우 처리가 끝나야 배치를 바꿀 수 있습니다."),
    NOT_LORD("주공만 이 자리에 카드를 앉힐 수 있습니다."),
    NOT_RULER("군주만 郡 방침을 걸 수 있습니다."),
    INVALID_COUNTY("아군 행정 현을 선택해 주세요."),
    COUNTY_OCCUPIED("이미 현령이 있거나 부임 중인 현입니다."),
    INVALID_PROVINCE("정찰할 수 있는 육상 지역을 선택해 주세요."),
    INVALID_NATION("사자를 보낼 수 있는 다른 세력을 선택해 주세요."),
    NOT_LIEUTENANT("군단장은 직접 거느린 NPC 부장만 맡을 수 있습니다."),
    NO_PLACEMENT("비울 배치가 없습니다."),
    UNCHANGED("이미 같은 입력이 걸려 있습니다."),
    INVALID_COMMANDERY("아군 현이 있는 군을 선택해 주세요."),
    NOT_COUNTY_AUTHORITY("이 현의 방침·공사를 정할 권한이 없습니다."),
    UPPER_POLICY_IN_FORCE("군 방침이 걸려 있어 현 방침을 바꿀 수 없습니다."),
    CORPS_NOT_FOUND("거느린 출전 군단을 찾을 수 없습니다."),
    NOTHING_TO_CLEAR("거둘 방침이 없습니다."),
    WAREHOUSE_NOT_READY("이 현의 창고가 아직 준비되지 않았습니다."),
    WORK_IN_PROGRESS("이미 진행 중인 공사가 있습니다."),
    WORK_COMPLETED("이미 완공한 공사입니다."),
    WORK_NOT_COMPLETED("감축할 성방 공사가 완공되지 않았습니다."),
    STATE_UNAVAILABLE("저장된 내정 상태를 확인할 수 없습니다."),
}

sealed interface DomesticAssessment {
    data class Eligible(val card: DomesticCard? = null, val person: DomesticPerson? = null) : DomesticAssessment
    data class Rejected(val reason: DomesticFailure) : DomesticAssessment
}

/** 縣令 자리에 실제로 앉은 인물. [controllerId] 는 그 자리의 방침을 정할 수 있는 장수(배치 카드면 주인, 발령이면 본인). */
data class HwihaSeatedMagistrate(val personId: Int, val controllerId: Int, val placed: Boolean, val retainerId: Int?)

enum class PolicySource { COMMANDERY, COUNTY, DEFAULT }
data class HwihaEffectivePolicy(val policy: CountyPolicy, val source: PolicySource, val seat: HwihaSeatedMagistrate?)

/**
 * 배치·방침·공사의 공유 판정(접수 사전검사 = 실행 재검사). 난수·시계·쓰기 없음. 권한 근거:
 * - 자리(縣令·사자)는 주공이 정한다(§2.4 「어느 자리에 앉을지 — 주공」). 정찰·군단장은 카드 주인이면 된다.
 * - 縣 방침·공사는 그 세력의 군주, 또는 그 縣의 현령 자리 주인(발령된 사람 장수 본인·배치된 카드의 주인)이 정한다.
 *   군주가 건 郡 방침이 있으면 군주가 아닌 장수는 縣 방침을 바꿀 수 없다(§2.4 「상위 방침의 범위 안에서만」).
 * - 郡 방침은 군주만 건다(太守·刺史는 2층 관직이라 이번 범위 밖).
 * - 군단 방침은 출전 군단의 주인이 건다.
 * 군주 = 그 세력에서 유일한 `officer_level == 12` 이면서 `hwihaLord == true` 인 장수(출사 투영과 같은 근거).
 */
object HwihaDomesticRules {
    const val RELATION_LIEUTENANT = "lieutenant"

    fun assessPlacement(request: PlacementRequest, state: HwihaDomesticProjection): DomesticAssessment = guarded {
        val base = cardRelation(request.actorId, request.cardId, state)
        if (base !is DomesticAssessment.Eligible) return@guarded base
        val actor = state.person(request.actorId)!!
        val person = base.person!!
        val current = HwihaPlacementState.read(person.meta)
        if (request.post == PlacementPost.NONE) {
            return@guarded if (current == null || (current.active == null && current.pending?.post == PlacementPost.NONE))
                reject(DomesticFailure.NO_PLACEMENT) else base
        }
        if (current?.pending == null && current?.active?.order?.let { it.post == request.post && it.target == request.target } == true)
            return@guarded reject(DomesticFailure.UNCHANGED)
        targetCheck(actor, request.post, request.target, person, base.card!!, state) ?: base
    }

    /** 카드 턴의 재검사: 현행(또는 막 현행이 될) 배치가 아직 유효한가. 자리 권한(주공)은 접수 때만 본다. */
    fun assessPlacementOrder(personId: Int, order: HwihaPlacementOrder, state: HwihaDomesticProjection): DomesticAssessment = guarded {
        val base = cardRelation(order.ownerGeneralId, order.retainerId, state)
        if (base !is DomesticAssessment.Eligible) return@guarded base
        if (base.person!!.id != personId) return@guarded reject(DomesticFailure.CARD_NOT_FOUND)
        if (order.post == PlacementPost.NONE) return@guarded base
        val owner = state.person(order.ownerGeneralId)!!
        targetCheck(owner, order.post, order.target, base.person, base.card!!, state, requireLord = false) ?: base
    }

    fun assessPolicy(request: PolicyRequest, state: HwihaDomesticProjection): DomesticAssessment = guarded {
        if (state.profile != RuleProfile.HWIHA) return@guarded reject(DomesticFailure.WRONG_RULE_PROFILE)
        val actor = state.person(request.actorId) ?: return@guarded reject(DomesticFailure.ACTOR_NOT_FOUND)
        val slot = when (val target = request.target) {
            is PolicyTarget.County -> {
                val county = state.county(target.countyId)?.takeIf { it.nationId > 0 && it.nationId == actor.nationId }
                    ?: return@guarded reject(DomesticFailure.INVALID_COUNTY)
                val ruler = rulerOf(county.nationId, state)
                if (ruler?.id != actor.id && actor.id !in countyControllers(county, state))
                    return@guarded reject(DomesticFailure.NOT_COUNTY_AUTHORITY)
                if (ruler?.id != actor.id && commanderySlot(county, state)?.isEmpty == false)
                    return@guarded reject(DomesticFailure.UPPER_POLICY_IN_FORCE)
                HwihaCountyPolicyState.read(county.meta)?.slot
            }
            is PolicyTarget.Commandery -> {
                if (actor.nationId <= 0 || rulerOf(actor.nationId, state)?.id != actor.id) return@guarded reject(DomesticFailure.NOT_RULER)
                if (state.counties.all { it.commanderyId == null }) return@guarded reject(DomesticFailure.STATE_UNAVAILABLE)
                if (state.counties.none { it.nationId == actor.nationId && it.commanderyId == target.commanderyId })
                    return@guarded reject(DomesticFailure.INVALID_COMMANDERY)
                state.nation(actor.nationId)?.let { HwihaCommanderyPolicies.read(it.meta)?.get(target.commanderyId)?.slot }
            }
            is PolicyTarget.Corps -> {
                val corps = deployedCorps(state).singleOrNull { it.orderId == target.orderId && it.ownerGeneralId == actor.id }
                    ?: return@guarded reject(DomesticFailure.CORPS_NOT_FOUND)
                HwihaCorpsPolicies.read(actor.meta)?.forOrder(corps.orderId)?.slot
            }
        }
        when {
            request.policy == null && (slot == null || slot.active == null && slot.pending?.let { it.policy == null } != false) ->
                reject(DomesticFailure.NOTHING_TO_CLEAR)
            slot?.pending == null && request.policy != null && slot?.active?.policy == request.policy -> reject(DomesticFailure.UNCHANGED)
            else -> DomesticAssessment.Eligible(person = actor)
        }
    }

    fun assessWork(request: WorkRequest, state: HwihaDomesticProjection): DomesticAssessment = guarded {
        if (state.profile != RuleProfile.HWIHA) return@guarded reject(DomesticFailure.WRONG_RULE_PROFILE)
        val actor = state.person(request.actorId) ?: return@guarded reject(DomesticFailure.ACTOR_NOT_FOUND)
        val county = state.county(request.countyId)?.takeIf { it.nationId > 0 && it.nationId == actor.nationId }
            ?: return@guarded reject(DomesticFailure.INVALID_COUNTY)
        if (rulerOf(county.nationId, state)?.id != actor.id && actor.id !in countyControllers(county, state))
            return@guarded reject(DomesticFailure.NOT_COUNTY_AUTHORITY)
        if (HwihaCountyWarehouse.read(county.meta, county.id) == null) return@guarded reject(DomesticFailure.WAREHOUSE_NOT_READY)
        val works = HwihaCountyWorks.read(county.meta)
        when {
            works?.active != null -> reject(DomesticFailure.WORK_IN_PROGRESS)
            works?.completed?.any { it.work == request.work } == true -> reject(DomesticFailure.WORK_COMPLETED)
            else -> DomesticAssessment.Eligible(person = actor)
        }
    }

    fun assessReduce(request: WorkRequest, state: HwihaDomesticProjection): DomesticAssessment = guarded {
        if (state.profile != RuleProfile.HWIHA) return@guarded reject(DomesticFailure.WRONG_RULE_PROFILE)
        val actor = state.person(request.actorId) ?: return@guarded reject(DomesticFailure.ACTOR_NOT_FOUND)
        val county = state.county(request.countyId)?.takeIf { it.nationId > 0 && it.nationId == actor.nationId }
            ?: return@guarded reject(DomesticFailure.INVALID_COUNTY)
        if (rulerOf(county.nationId, state)?.id != actor.id && actor.id !in countyControllers(county, state))
            return@guarded reject(DomesticFailure.NOT_COUNTY_AUTHORITY)
        val works = HwihaCountyWorks.read(county.meta)
        if (works?.active != null) return@guarded reject(DomesticFailure.WORK_IN_PROGRESS)
        if (request.work != DomesticWork.FORTIFICATION ||
            works?.completed?.none { it.work == DomesticWork.FORTIFICATION } != false)
            return@guarded reject(DomesticFailure.WORK_NOT_COMPLETED)
        DomesticAssessment.Eligible(person = actor)
    }

    /** 세력의 군주. 둘 이상이거나 주공 표지가 없으면 없음이다. */
    fun rulerOf(nationId: Int, state: HwihaDomesticProjection): DomesticPerson? {
        if (nationId <= 0) return null
        return state.people.filter { it.nationId == nationId && it.officerLevel == 12 }.singleOrNull()
            ?.takeIf { HwihaLordStatus.read(it.meta) }
    }

    /** 縣 방침·공사를 정할 수 있는 현령 자리 주인들(군주 제외): 발령된 사람 장수 본인, 현행 배치 카드의 주인. */
    fun countyControllers(county: DomesticCounty, state: HwihaDomesticProjection): Set<Int> = buildSet {
        for (person in state.people.sortedBy { it.id }) {
            val assignment = HwihaCountyAssignment.read(person.meta)
            if (assignment != null && assignment.countyId == county.id && assignment.nationId == county.nationId &&
                person.nationId == county.nationId) add(person.id)
            val active = HwihaPlacementState.read(person.meta)?.active
            if (active != null && active.order.post == PlacementPost.MAGISTRATE && active.order.target == PlacementTarget.County(county.id) &&
                assessPlacementOrder(person.id, active.order, state) is DomesticAssessment.Eligible) add(active.order.ownerGeneralId)
        }
    }

    /**
     * 실제로 자리에 앉은 현령. 배치 카드는 현행 배치가 유효하고 도착했으며 지금 그 縣의 省에 서 있어야 하고,
     * 발령된 사람 장수는 수락한 부임 목표가 그 縣이고 지금 그 縣의 省에 서 있어야 한다. 둘 이상이면 장수 id 가 작은 쪽.
     * 그 省에 선 장수의 저장값이 오염됐으면 예외다 — 빈자리로 바꿔 읽지 않는다.
     */
    fun seatedMagistrate(county: DomesticCounty, state: HwihaDomesticProjection): HwihaSeatedMagistrate? {
        val province = county.provinceId ?: return null
        val seats = state.peopleAt(province).mapNotNull { person ->
            if (person.inBattle || person.nationId != county.nationId) return@mapNotNull null
            // Only people standing in this county are read: a corrupt record elsewhere cannot unseat this county.
            val active = HwihaPlacementState.read(person.meta)?.active
            if (active != null && active.arrivedAt != null && active.order.post == PlacementPost.MAGISTRATE &&
                active.order.target == PlacementTarget.County(county.id) &&
                assessPlacementOrder(person.id, active.order, state) is DomesticAssessment.Eligible)
                return@mapNotNull HwihaSeatedMagistrate(person.id, active.order.ownerGeneralId, true, active.order.retainerId)
            val assignment = HwihaCountyAssignment.read(person.meta)
            if (assignment != null && person.userOwned && assignment.countyId == county.id && assignment.nationId == county.nationId)
                return@mapNotNull HwihaSeatedMagistrate(person.id, person.id, false, null)
            null
        }
        return seats.firstOrNull()
    }

    fun commanderySlot(county: DomesticCounty, state: HwihaDomesticProjection): HwihaPolicySlot? {
        val commandery = county.commanderyId ?: return null
        val nation = state.nation(county.nationId) ?: return null
        return HwihaCommanderyPolicies.read(nation.meta)?.get(commandery)?.slot
    }

    /** 郡 방침(현행) > 縣 방침(현행, 앉은 현령이 있을 때) > 기본 방침(§8.2 「빈자리는 기본 방침으로 자동 운영」). */
    fun effectivePolicy(county: DomesticCounty, state: HwihaDomesticProjection, design: DomesticDesign): HwihaEffectivePolicy {
        val seat = seatedMagistrate(county, state)
        commanderySlot(county, state)?.active?.let { return HwihaEffectivePolicy(CountyPolicy.valueOf(it.policy), PolicySource.COMMANDERY, seat) }
        val own = HwihaCountyPolicyState.read(county.meta)?.slot?.active
        if (seat != null && own != null) return HwihaEffectivePolicy(CountyPolicy.valueOf(own.policy), PolicySource.COUNTY, seat)
        return HwihaEffectivePolicy(design.defaultCountyPolicy, PolicySource.DEFAULT, seat)
    }

    /** 사람 장수의 발령 부임(대기 포함)과 다른 카드의 배치가 잡은 縣令 자리. */
    fun magistracyClaimed(countyId: Int, exceptPersonId: Int, state: HwihaDomesticProjection): Boolean {
        val county = state.county(countyId) ?: return true
        return state.people.filter { it.id != exceptPersonId }.any { person ->
            val placement = HwihaPlacementState.read(person.meta)
            val assignment = HwihaCountyAssignment.read(person.meta)
            val dispatch = HwihaDispatchState.read(person.meta)
            (placement?.claimsMagistracy(countyId) == true && person.nationId == county.nationId) ||
                (assignment?.countyId == countyId && assignment.nationId == person.nationId && assignment.nationId == county.nationId) ||
                (dispatch?.countyId == countyId && dispatch.status == DispatchStatus.PENDING && dispatch.nationId == person.nationId &&
                    dispatch.nationId == county.nationId)
        }
    }

    /** 사람 장수 meta 의 출전 기록. 오염되면 예외(호출자가 STATE_UNAVAILABLE 로 바꾼다). */
    fun deployedCorps(state: HwihaDomesticProjection): List<HwihaDeployedCorps> = state.people.sortedBy { it.id }.flatMap { person ->
        HwihaDeploymentState.read(person.meta)?.corps.orEmpty().also { rows -> require(rows.all { it.ownerGeneralId == person.id }) }
    }

    private fun cardRelation(actorId: Int, cardId: Int, state: HwihaDomesticProjection): DomesticAssessment {
        if (state.profile != RuleProfile.HWIHA) return reject(DomesticFailure.WRONG_RULE_PROFILE)
        val actor = state.person(actorId) ?: return reject(DomesticFailure.ACTOR_NOT_FOUND)
        val card = state.cards.singleOrNull { it.id == cardId }?.takeIf { it.masterId == actor.id }
            ?: return reject(DomesticFailure.CARD_NOT_FOUND)
        val generalId = card.generalId ?: return reject(DomesticFailure.CARD_NOT_ON_MAP)
        val person = state.person(generalId)?.takeIf { it.id != actor.id } ?: return reject(DomesticFailure.CARD_NOT_FOUND)
        if (state.cards.count { it.generalId == person.id } != 1) return reject(DomesticFailure.STATE_UNAVAILABLE)
        if (person.userOwned) return reject(DomesticFailure.HUMAN_CARD)
        if (person.nationId != actor.nationId) return reject(DomesticFailure.DIFFERENT_NATION)
        if (deployedCorps(state).any { it.commanderGeneralId == person.id }) return reject(DomesticFailure.CARD_DEPLOYED)
        if (person.inBattle) return reject(DomesticFailure.CARD_IN_BATTLE)
        return DomesticAssessment.Eligible(card, person)
    }

    private fun targetCheck(owner: DomesticPerson, post: PlacementPost, target: PlacementTarget, person: DomesticPerson,
        card: DomesticCard, state: HwihaDomesticProjection, requireLord: Boolean = true): DomesticAssessment? {
        val lord = !requireLord || (owner.nationId > 0 && HwihaLordStatus.read(owner.meta))
        return when (post) {
            PlacementPost.MAGISTRATE -> {
                if (!lord) return reject(DomesticFailure.NOT_LORD)
                val countyId = (target as PlacementTarget.County).countyId
                val county = state.county(countyId)?.takeIf { it.nationId > 0 && it.nationId == owner.nationId }
                    ?: return reject(DomesticFailure.INVALID_COUNTY)
                if (county.provinceId == null) return reject(DomesticFailure.STATE_UNAVAILABLE)
                if (magistracyClaimed(countyId, person.id, state)) return reject(DomesticFailure.COUNTY_OCCUPIED)
                null
            }
            PlacementPost.ENVOY -> {
                if (!lord) return reject(DomesticFailure.NOT_LORD)
                val nationId = (target as PlacementTarget.Nation).nationId
                val nation = state.nation(nationId)
                if (nation == null || nationId == owner.nationId || nation.capitalCityId == null) reject(DomesticFailure.INVALID_NATION)
                else null
            }
            PlacementPost.SCOUT -> {
                val provinces = state.landProvinceIds ?: return reject(DomesticFailure.STATE_UNAVAILABLE)
                if ((target as PlacementTarget.Province).provinceId !in provinces) reject(DomesticFailure.INVALID_PROVINCE) else null
            }
            PlacementPost.CORPS_COMMANDER ->
                if (card.relation != RELATION_LIEUTENANT || person.npcState != 2) reject(DomesticFailure.NOT_LIEUTENANT) else null
            PlacementPost.NONE -> null
        }
    }

    private inline fun guarded(block: () -> DomesticAssessment): DomesticAssessment =
        try { block() } catch (_: IllegalArgumentException) { reject(DomesticFailure.STATE_UNAVAILABLE) }

    private fun reject(reason: DomesticFailure) = DomesticAssessment.Rejected(reason)
}
