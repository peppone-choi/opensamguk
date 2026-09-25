package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.controller.RetinueController
import opensamguk.gameapi.dto.*
import opensamguk.infra.seed.HwihaCountyProductionJson
import opensamguk.logic.economy.CountyIncome
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.HwihaAptitude
import opensamguk.logic.input.HwihaPersonPolicyState
import opensamguk.logic.renown.RenownAssessment
import opensamguk.logic.renown.RenownEventKind
import opensamguk.logic.renown.RenownEvents
import opensamguk.logic.renown.RenownRules
import opensamguk.logic.retainer.RetainerRules
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class HwihaCampForbidden : RuntimeException()

/** 휘하 조회 공통 소유 확인 — `?generalId=` 장수의 `userId` 가 principal 과 같아야 한다. 없는 장수도 403 이다. */
internal fun ownedHwihaGeneral(generals: GeneralReadRepository, generalId: Int, userId: Long): GeneralReadEntity {
    val actor = generals.findById(generalId).orElse(null) ?: throw HwihaCampForbidden()
    if (userId <= 0 || userId > Int.MAX_VALUE || actor.userId?.toLongOrNull() != userId) throw HwihaCampForbidden()
    return actor
}

/** 휘하 조회 공통 월드 문 — 처리 월드가 아니면 `UNAVAILABLE`, 휘하 규칙이 아니면 `WRONG_RULE_PROFILE`, 통과면 null. */
internal fun hwihaGate(worlds: WorldStateReadRepository, actor: GeneralReadEntity): String? {
    val world = worlds.findProcessWorld() ?: return "UNAVAILABLE"
    if (actor.worldId != world.id) return "UNAVAILABLE"
    if (world.config["ruleProfile"] != "HWIHA") return "WRONG_RULE_PROFILE"
    return null
}

/**
 * 휘하 화면 조회 네 가지(월단평·창고·현 특산·휘하 카드). **읽기만 한다** — 쓰기·ChangeRecorder 없음.
 *
 * 인증은 계책 손패([HwihaStratagemHandReader])와 같다: `?generalId=` 장수의 `userId` 가 principal 과
 * 같아야 하고 아니면 [HwihaCampForbidden](403). 휘하 규칙이 아닌 월드는 200 + `WRONG_RULE_PROFILE` 이다.
 *
 * 규칙 수치는 엔진과 같은 함수를 부른다 — 코스트 [RenownRules.personCost], 이탈 순서
 * [RenownAssessment.departures], 월단평 키 [RenownAssessment.STAMP_KEY]·[RenownAssessment.RANKING_KEY].
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class HwihaCampReader(
    private val generals: GeneralReadRepository,
    private val worlds: WorldStateReadRepository,
    private val nations: NationReadRepository,
    private val cities: CityReadRepository,
    private val retainers: RetainerReadRepository,
    private val gameKv: GameKvReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val ledgers: HwihaCampLedgers,
    private val geography: HwihaCityGeography,
    private val objectMapper: ObjectMapper,
) {
    /** 엔진과 같은 런타임 산지 표(`infra` 의 hwiha/county-production-v1.json). 테스트가 바꿔 끼운다. */
    internal var production: Map<Int, Resources> = HwihaCountyProductionJson.table()

    // ── 월단평 ─────────────────────────────────────────────────────────────
    fun yuedan(generalId: Int, userId: Long): HwihaYuedanResponse {
        val actor = owned(generalId, userId)
        gate(actor)?.let { return HwihaYuedanResponse(it) }
        val everyone = generals.findAll().associateBy { it.id }
        val cost = retinueCost(actor.id) { everyone[it] }
        val renown = renownOf(actor)
        val self = HwihaYuedanSelf(actor.id, renown, cost, renown != null && cost != null && cost > renown)
        val pending = pendingEvents(actor)
        val stamp = kv(RenownAssessment.STAMP_KEY)?.let { node -> if (node.isTextual) node.asText() else node.toString() }
        val published = kv(RenownAssessment.RANKING_KEY)
            ?: return HwihaYuedanResponse("NOT_ASSESSED", stamp, self, selfPendingEvents = pending)
        if (!published.isArray || !published.all { it.isIntegralNumber && it.canConvertToInt() })
            return HwihaYuedanResponse("UNAVAILABLE", stamp, self, selfPendingEvents = pending)
        val reasons = reasonsFor(stamp)
        val nationById = nations.findAll().associateBy { it.id }
        // 순위는 발표된 자리 그대로다(1부터). 그 뒤 사라졌거나 명망을 읽을 수 없는 장수는 빠지고 자리는 남는다.
        val ranking = published.map { it.intValue() }.mapIndexedNotNull { index, id ->
            val general = everyone[id] ?: return@mapIndexedNotNull null
            val value = renownOf(general) ?: return@mapIndexedNotNull null
            val nation = nationById[general.nationId]?.takeIf { general.nationId != 0 }
            HwihaYuedanRow(index + 1, general.id, general.name, general.nationId, nation?.name,
                nation?.color?.takeIf { it.isNotBlank() }, value, reasons[general.id].orEmpty())
        }
        return HwihaYuedanResponse("READY", stamp, self, ranking, pending)
    }

    /**
     * 마지막 월단평의 사유(엔진 [RenownAssessment.REASONS_KEY]). 발표 도장과 같은 달의 것만 쓴다 — 도장이
     * 다르면 다른 달의 사유라 싣지 않는다. 읽을 수 없는 줄은 빠진다(사유는 곁들임이다).
     */
    private fun reasonsFor(stamp: String?): Map<Int, List<HwihaRenownReasonDto>> {
        val node = kv(RenownAssessment.REASONS_KEY) ?: return emptyMap()
        if (stamp == null || node.path("stamp").asText(null) != stamp) return emptyMap()
        val byGeneral = node.path("byGeneral").takeIf { it.isObject } ?: return emptyMap()
        return byGeneral.fields().asSequence().mapNotNull { (key, rows) ->
            val id = key.toIntOrNull() ?: return@mapNotNull null
            id to rows.mapNotNull { row ->
                val kind = RenownEventKind.ofKey(row.path("kind").asText("")) ?: return@mapNotNull null
                val count = row.path("count").takeIf { it.canConvertToInt() }?.intValue() ?: return@mapNotNull null
                val amount = row.path("amount").takeIf { it.canConvertToInt() }?.intValue() ?: return@mapNotNull null
                HwihaRenownReasonDto(kind.key, kind.label, count, amount)
            }
        }.toMap()
    }

    /** 본인 집계 — 본인만 받는다(이 응답은 소유 확인을 지난 장수 것이다). */
    private fun pendingEvents(actor: GeneralReadEntity): List<HwihaRenownPendingEventDto> =
        RenownEvents.entries(actor.meta).map {
            HwihaRenownPendingEventDto(it.kind.key, it.kind.label, it.stamp, it.source?.name, it.source?.label,
                it.kind.amountIn(RenownAssessment.CANON))
        }

    // ── 縣 창고 ────────────────────────────────────────────────────────────
    fun warehouses(generalId: Int, userId: Long): HwihaWarehousesResponse {
        val actor = owned(generalId, userId)
        gate(actor)?.let { return HwihaWarehousesResponse(it) }
        val scope = if (actor.nationId != 0) cities.findByNationIdOrderByIdAsc(actor.nationId)
            else listOfNotNull(cities.findById(actor.cityId).orElse(null))
        val capital = actor.nationId.takeIf { it != 0 }?.let { nations.findById(it).orElse(null)?.capitalCityId }
        val places = placesOrEmpty()
        var invalid = 0
        val rows = scope.mapNotNull { city ->
            val warehouse = try { CountyWarehouse.read(city.meta, city.id) }
                catch (_: IllegalArgumentException) { invalid++; null } ?: return@mapNotNull null
            val stock = warehouse.stock
            HwihaWarehouseDto(city.id, city.name, places[city.id]?.commanderyName, city.id == capital,
                city.supplyState != 0, HwihaStockDto(stock.money, stock.grain, stock.iron, stock.timber, stock.horses))
        }.sortedWith(compareBy({ !it.isCapital }, { it.cityId }))
        return HwihaWarehousesResponse("READY", rows, invalid)
    }

    // ── 縣 특산 ────────────────────────────────────────────────────────────
    /** @return 없는 城이면 null(404). */
    fun county(cityId: Int, generalId: Int, userId: Long): HwihaCountyResponse? {
        val actor = owned(generalId, userId)
        val city = cities.findById(cityId).orElse(null) ?: return null
        gate(actor)?.let { return HwihaCountyResponse(it, city.id, city.name) }
        // resolve() 는 자체 트랜잭션 프록시다 — 거기서 던진 것을 여기서 삼키면 바깥 트랜잭션이 rollback-only 로
        // 남아 커밋에서 터진다. 그래서 잡지 않는다. 번들 해석(순수 코드)만 잡는다.
        val selected = artifacts.resolve()?.artifacts ?: return HwihaCountyResponse("UNAVAILABLE", city.id, city.name)
        val jurisdiction = try { geography.places(selected)[city.id]?.jurisdictionId }
            catch (_: RuntimeException) { return HwihaCountyResponse("UNAVAILABLE", city.id, city.name) }
        val credited = creditedSites(city)
        val specialties = jurisdiction?.let { ledgers.productionByJurisdiction[it] }.orEmpty().map {
            HwihaSpecialtyDto(it.resource, RESOURCE_LABELS[it.resource] ?: it.resource,
                monthly = credited?.let { sites -> siteAmount(sites, it.resource) }, ledgerMonthly = it.ledgerMonthly)
        }
        return HwihaCountyResponse("READY", city.id, city.name, specialties)
    }

    /**
     * 엔진 월 세입(`HwihaMonthlyCountyIncome`)이 이 縣 창고에 이번 달 넣을 산지 몫(철·목재·말).
     * 엔진과 같은 식(`CountyIncome.monthly`)에 같은 런타임 표를 넣는다 — 주인 없음·보급 끊김·창고 없음이면 0 이다.
     * 창고 meta 가 깨졌으면 엔진도 그 縣을 건너뛰므로 null(모름)로 둔다.
     */
    private fun creditedSites(city: CityReadEntity): Resources? {
        val warehouse = try { CountyWarehouse.read(city.meta, city.id) } catch (_: IllegalArgumentException) { return null }
        if (warehouse == null) return Resources()
        val state = try {
            CountyIncome.CountyState(city.nationId, city.population, city.commerce, city.commerceMax,
                city.agriculture, city.agricultureMax, supplied = city.supplyState != 0)
        } catch (_: IllegalArgumentException) { return null }
        val produced = CountyIncome.monthly(state, sites = production[city.id] ?: Resources())
        return Resources(iron = produced.iron, timber = produced.timber, horses = produced.horses)
    }

    private fun siteAmount(sites: Resources, resource: String): Long? = when (resource) {
        "IRON" -> sites.iron
        "TIMBER" -> sites.timber
        "HORSE" -> sites.horses
        else -> null
    }

    // ── 휘하 카드 ──────────────────────────────────────────────────────────
    fun retinue(generalId: Int, userId: Long): HwihaRetinueResponse {
        val actor = owned(generalId, userId)
        gate(actor)?.let { return HwihaRetinueResponse(it) }
        val renown = renownOf(actor)
        val cards = retainers.retainersOf(actor.id)
        val people = cards.associate { card -> card.id to card.generalId?.let { generals.findById(it).orElse(null) } }
        val costs = cards.associate { card -> card.id to people[card.id]?.let(::personCost) }
        val costSum = if (costs.values.any { it == null }) null else costs.values.sumOf { requireNotNull(it) }
        val over = renown != null && costSum != null && costSum > renown
        val order = if (!over) emptyMap() else RenownAssessment.departures(requireNotNull(renown),
            cards.mapNotNull { card -> costs[card.id]?.let { RenownAssessment.RetainerCard(card.id, it, card.loyalty) } })
            .withIndex().associate { (index, id) -> id to index + 1 }
        val lordHome = ledgers.nativeCountyOf(actor)
        val homes = cards.associate { card -> card.id to people[card.id]?.let(ledgers::nativeCountyOf) }
        // 한글 이름은 활성 세계 판의 城 표에서 푼다. 향당이 하나도 없으면 번들을 열지 않는다.
        val countyNames = if (homes.values.none { it != null }) null else countyNamesOrNull()
        val rows = cards.map { card ->
            val person = people[card.id]
            val home = homes[card.id]
            HwihaPersonCardDto(
                retainerId = card.id, generalId = card.generalId, name = person?.name ?: card.name,
                picture = person?.picture, imageServer = person?.imageServer ?: 0, loyalty = card.loyalty,
                roleLabel = RetainerRules.ROLE_LABELS[card.role] ?: card.role,
                taskLabel = RetainerRules.TASK_LABELS[card.task] ?: card.task,
                stats = person?.let { HwihaFiveStatsDto(it.leadership, it.strength, it.intel, it.politics, it.charm) },
                cost = costs[card.id],
                aptitudes = person?.let(::aptitudes),
                bonds = listOfNotNull(home?.let {
                    HwihaBondDto("HYANGDANG", "향당", nativeCountyName = countyNames?.korean(it), nativeCountyHanja = it.hanja,
                        sameAsLord = lordHome != null && ledgers.sameHome(it, lordHome))
                }),
                departureOrder = order[card.id],
                locationCityId = person?.cityId,
            )
        }
        val units = retainers.bugoksOf(actor.id).map(RetinueController::bugokDto)
        return HwihaRetinueResponse("READY", renown, costSum, over, rows, units)
    }

    // ── 공용 ───────────────────────────────────────────────────────────────
    private fun owned(generalId: Int, userId: Long): GeneralReadEntity = ownedHwihaGeneral(generals, generalId, userId)

    private fun gate(actor: GeneralReadEntity): String? = hwihaGate(worlds, actor)

    private fun renownOf(general: GeneralReadEntity): Int? =
        try { HwihaPersonPolicyState.read(general.meta)?.renownCapacity } catch (_: IllegalArgumentException) { null }

    /** 결손 카드가 하나라도 있으면 총합은 미상이다. 검증되지 않은 능력치를 0 코스트로 취급하지 않는다. */
    private fun retinueCost(masterId: Int, lookup: (Int) -> GeneralReadEntity?): Int? {
        val costs = retainers.retainersOf(masterId).map { card -> card.generalId?.let(lookup)?.let(::personCost) }
        return if (costs.any { it == null }) null else costs.sumOf { requireNotNull(it) }
    }

    private fun personCost(person: GeneralReadEntity): Int? = try {
        if (HwihaPersonPolicyState.read(person.meta) == null) null else
            RenownRules.personCost(person.leadership, person.strength, person.intel, person.politics, person.charm)
    } catch (_: IllegalArgumentException) { null }

    private fun aptitudes(person: GeneralReadEntity): HwihaAptitudesDto? = try {
        HwihaAptitude.compute(HwihaAptitude.Stats(person.leadership, person.strength, person.intel, person.politics, person.charm))
            .let { HwihaAptitudesDto(it.command, it.administration, it.strategy, it.envoy) }
    } catch (_: IllegalArgumentException) { null }

    private fun kv(key: String) = gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", key)
        ?.let { row -> runCatching { objectMapper.readTree(row.value) }.getOrNull() }
        ?.takeUnless { it.isNull || it.isMissingNode }

    /** 郡 이름은 곁들임이다 — 번들 내용을 못 읽어도 창고 목록은 낸다(이름만 null). */
    private fun placesOrEmpty(): Map<Int, HwihaCityGeography.Place> {
        val bundle = artifacts.resolve()?.artifacts ?: return emptyMap()
        return try { geography.places(bundle) } catch (_: RuntimeException) { emptyMap() }
    }

    /** 한글 지명은 곁들임이다 — 번들 내용을 못 읽으면 이름만 null 이고 결속은 그대로 낸다. */
    private fun countyNamesOrNull(): HwihaCityGeography.CountyNames? {
        val bundle = artifacts.resolve()?.artifacts ?: return null
        return try { geography.countyNames(bundle) } catch (_: RuntimeException) { null }
    }

    companion object {
        /** 사용자 확정 표기(2026-09): 전→금, 곡→쌀. */
        val RESOURCE_LABELS = mapOf("MONEY" to "금", "GRAIN" to "쌀", "IRON" to "철", "TIMBER" to "목재", "HORSE" to "말")
    }
}
