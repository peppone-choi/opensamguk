package opensamguk.gameapi.read

import opensamguk.logic.domestic.PlacementState
import opensamguk.logic.domestic.PolicySetting
import opensamguk.logic.domestic.PolicyOrder
import opensamguk.logic.domestic.PolicySlot
import opensamguk.logic.domestic.CountyPolicyState
import opensamguk.logic.domestic.CommanderyPolicies
import opensamguk.logic.domestic.CorpsPolicyAssignments
import opensamguk.logic.domestic.CountyWorks

import opensamguk.logic.domestic.PlacementPost
import opensamguk.logic.domestic.CountyPolicy
import opensamguk.logic.domestic.CorpsPolicy
import opensamguk.logic.domestic.DomesticWork
import opensamguk.logic.domestic.PlacementTarget
import opensamguk.logic.domestic.PolicyTarget
import opensamguk.logic.domestic.PlacementRequest
import opensamguk.logic.domestic.PolicyRequest
import opensamguk.logic.domestic.WorkRequest
import opensamguk.logic.domestic.CountyLevels
import opensamguk.logic.domestic.SeatStats
import opensamguk.logic.domestic.DomesticEffects

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCard
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticBugok
import opensamguk.logic.domestic.DomesticDiplomacy
import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.domestic.DomesticFailure
import opensamguk.logic.domestic.DomesticAssessment
import opensamguk.logic.domestic.DomesticRules

import opensamguk.logic.domestic.DomesticDesign
import opensamguk.gameapi.dto.*
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.*
import opensamguk.logic.world.StrategicNodeRef
import opensamguk.infra.seed.UnitProfilesJson
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class DomesticForbidden : RuntimeException()

/** 한 REPEATABLE_READ 스냅샷에서 만든 공유 판정 투영과 표시 이름. [failure] 가 있으면 투영이 없다. */
data class DomesticSnapshot(
    val state: DomesticProjection? = null,
    val failure: String? = null,
    val countyNames: Map<Int, String> = emptyMap(),
    val commanderyNames: Map<String, String> = emptyMap(),
    val warehouseStocks: Map<Int, Resources> = emptyMap(),
    val countyLevels: Map<Int, CountyLevels> = emptyMap(),
    val cityMilitaryStates: Map<Int, CityMilitaryState> = emptyMap(),
    val cityMilitaryTroops: Map<Int, Int> = emptyMap(),
)

/**
 * 휘하 내정 입력의 DB 읽기(접수 사전검사와 세 조회 API 가 같이 쓴다). 쓰기·ChangeRecorder 없음.
 * 엔진과 같은 투영([DomesticProjection])을 DB 행으로 만든다: 위치는 `general_spatial_position`, 郡은 런타임 지도
 * `meta.junCh`([CityGeography]), 縣은 부팅 판 결속의 행정 縣. 향당 보너스는 조회·접수에 쓰지 않아 비워 둔다.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class DomesticReader(
    private val generals: GeneralReadRepository,
    private val retainers: RetainerReadRepository,
    private val nations: NationReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val spatial: SpatialStateReadRepository,
    private val geography: CityGeography,
    private val diplomacy: DiplomacyReadRepository,
    private val sieges: SiegeReadRepository,
) {
    fun requireOwner(actorId: Int, userId: Long) {
        if (actorId <= 0 || userId <= 0 || userId > Int.MAX_VALUE) throw DomesticForbidden()
        if (generals.findById(actorId).orElse(null)?.userId?.toLongOrNull() != userId) throw DomesticForbidden()
    }

    fun snapshot(): DomesticSnapshot = try {
        val selected = artifacts.resolve()
        if (selected == null) DomesticSnapshot(failure = "UNAVAILABLE")
        else {
            val config = selected.world.config
            val profile = opensamguk.logic.input.WorldRuleProfile.require(config)
            if (profile != RuleProfile.HWIHA) DomesticSnapshot(failure = "WRONG_RULE_PROFILE")
            else {
                val bundle = requireNotNull(selected.artifacts) { "HWIHA requires pinned Han artifacts" }
                val topology = bundle.projection.topology
                val people = generals.findAll(); val cards = retainers.findAll(); val nationRows = nations.findAll()
                require(people.all { it.worldId == selected.world.id } && cards.all { it.worldId == selected.world.id })
                val positions = spatial.readSnapshot(selected.world.id, topology).generalPositionSnapshot
                val places = geography.places(bundle)
                val admin = bundle.projection.administrativeCountyIds
                val counties = selected.cities.filter { it.id in admin }.sortedBy { it.id }
                DomesticSnapshot(
                    state = DomesticProjection(
                        profile = profile,
                        now = Phase(selected.world.currentYear, selected.world.currentMonth, selected.world.currentPhase),
                        people = people.sortedBy { it.id }.map { g ->
                            val position = positions.stateFor(g.id)
                            DomesticPerson(g.id, g.name, g.nationId, (g.userId?.toLongOrNull() ?: 0) > 0, g.npcState, g.officerLevel,
                                g.leadership, g.strength, g.intel, g.politics, g.charm,
                                (position?.node as? StrategicNodeRef.LandProvince)?.id, position?.battlefield != null, g.meta, g.injury,
                                g.gold, g.rice)
                        },
                        cards = cards.sortedBy { it.id }.map { DomesticCard(it.id, it.masterGeneralId, it.generalId, it.relation, it.name) },
                        counties = counties.map { c ->
                            DomesticCounty(c.id, c.name, c.nationId, bundle.projection.bindingsByCityId[c.id]?.landProvinceId,
                                places[c.id]?.commanderyHanja, c.meta)
                        },
                        nations = nationRows.sortedBy { it.id }.map { DomesticNation(it.id, it.name, it.capitalCityId, it.meta,
                            it.level, it.gold, it.rice, it.tech) },
                        landProvinceIds = topology.landProvinceIds,
                        bugoks = retainers.allBugoks().map { DomesticBugok(it.id, it.masterGeneralId, it.crewTypeId, it.training) },
                        countyAdjacency = admin.associateWith { countyId ->
                            bundle.cityConst.byId(countyId)?.path?.keys?.filter { it in admin }?.toSet() ?: emptySet()
                        },
                        supportedCrewTypeIds = UnitProfilesJson.loadDefault().profiles.map { it.crewTypeId }.toSet(),
                        diplomacy = diplomacy.findAll().map { DomesticDiplomacy(it.srcNationId, it.destNationId, it.stateCode, it.term) },
                        activeSiegeCountyIds = sieges.activeCountyIds(),
                    ),
                    countyNames = counties.associate { it.id to (places[it.id]?.displayName ?: it.name) },
                    commanderyNames = counties.mapNotNull { c ->
                        val place = places[c.id] ?: return@mapNotNull null
                        val id = place.commanderyHanja ?: return@mapNotNull null
                        id to (place.commanderyName ?: id)
                    }.toMap(),
                    warehouseStocks = counties.mapNotNull { c ->
                        try { CountyWarehouse.read(c.meta, c.id)?.let { c.id to it.stock } } catch (_: IllegalArgumentException) { null }
                    }.toMap(),
                    countyLevels = counties.associate { c -> c.id to CountyLevels(c.population, c.populationMax,
                        c.agriculture, c.agricultureMax, c.commerce, c.commerceMax, c.security, c.securityMax,
                        c.trust, c.defense, c.defenseMax, c.wall, c.wallMax) },
                    cityMilitaryStates = counties.mapNotNull { c ->
                        try { c.id to CityMilitaryState.read(c.meta, c.defense.coerceAtLeast(0)) }
                        catch (_: IllegalArgumentException) { null }
                    }.toMap(),
                    cityMilitaryTroops = counties.mapNotNull { c ->
                        try { c.id to CityMilitaryState.read(c.meta, c.defense.coerceAtLeast(0)).troops }
                        catch (_: IllegalArgumentException) { null }
                    }.toMap(),
                )
            }
        }
    } catch (_: IllegalArgumentException) { DomesticSnapshot(failure = "UNAVAILABLE") }
      catch (_: IllegalStateException) { DomesticSnapshot(failure = "UNAVAILABLE") }
      catch (_: java.io.IOException) { DomesticSnapshot(failure = "UNAVAILABLE") }

    fun posts(actorId: Int, userId: Long): PostsResponse {
        requireOwner(actorId, userId)
        return DomesticViews.posts(actorId, snapshot())
    }

    fun policies(actorId: Int, userId: Long): PoliciesResponse {
        requireOwner(actorId, userId)
        return DomesticViews.policies(actorId, snapshot())
    }

    fun works(actorId: Int, userId: Long): WorksResponse {
        requireOwner(actorId, userId)
        return DomesticViews.works(actorId, snapshot())
    }
}

/** 조회 응답 조립(순수). 접수·엔진과 같은 [DomesticRules] 판정만 쓴다. */
object DomesticViews {
    private val design get() = DomesticDesign.CANON

    fun posts(actorId: Int, snapshot: DomesticSnapshot): PostsResponse {
        val state = snapshot.state ?: return PostsResponse(snapshot.failure ?: "UNAVAILABLE")
        val actor = state.person(actorId) ?: return PostsResponse("UNAVAILABLE")
        return try {
            val cards = state.cards.filter { it.masterId == actorId }.sortedBy { it.id }.map { card ->
                val person = card.generalId?.let(state::person)
                val placement = person?.let { PlacementState.read(it.meta) }
                // Probe placeability with a release-agnostic scout request: it checks only the card relation.
                val probe = DomesticRules.assessPlacement(PlacementRequest(actorId, card.id, PlacementPost.SCOUT,
                    PlacementTarget.Province(state.landProvinceIds?.minOrNull() ?: "none")), state)
                val blocked = (probe as? DomesticAssessment.Rejected)?.reason
                    ?.takeUnless { it in setOf(DomesticFailure.INVALID_PROVINCE, DomesticFailure.UNCHANGED) }
                PlacementCardDto(card.id, card.generalId, person?.name ?: "", card.relation, person?.node,
                    blocked == null, blocked?.let { ReasonDto(it.name, it.message) },
                    placement?.active?.let { active ->
                        ActivePlacementDto(active.order.post.name, active.order.post.label, target(active.order.target, snapshot),
                            active.since, active.arrivedAt, if (active.arrivedAt == null) "MOVING" else "ARRIVED")
                    },
                    placement?.pending?.let { order ->
                        PlacementOrderDto(order.requestId, order.post.name, order.post.label, target(order.target, snapshot), order.requestedAt)
                    })
            }
            val lord = actor.nationId > 0 && LordStatus.read(actor.meta)
            val notLord = ReasonDto(DomesticFailure.NOT_LORD.name, DomesticFailure.NOT_LORD.message)
            val counties = state.counties.filter { it.nationId == actor.nationId && actor.nationId > 0 }.map { county ->
                PostTargetDto(countyId = county.id, name = snapshot.countyNames[county.id] ?: county.name,
                    commanderyName = county.commanderyId?.let { snapshot.commanderyNames[it] ?: it },
                    occupied = DomesticRules.magistracyClaimed(county.id, 0, state))
            }
            val envoys = state.nations.filter { it.id != actor.nationId && it.capitalCityId != null }
                .map { PostTargetDto(nationId = it.id, name = it.name) }
            PostsResponse("READY", now = state.now, cards = cards, posts = listOf(
                PostOptionDto(PlacementPost.MAGISTRATE.name, PlacementPost.MAGISTRATE.label, lord, notLord.takeUnless { lord }, counties),
                PostOptionDto(PlacementPost.CORPS_COMMANDER.name, PlacementPost.CORPS_COMMANDER.label, true, null, null),
                PostOptionDto(PlacementPost.ENVOY.name, PlacementPost.ENVOY.label, lord, notLord.takeUnless { lord }, envoys),
                PostOptionDto(PlacementPost.SCOUT.name, PlacementPost.SCOUT.label, state.landProvinceIds != null, null, null),
                PostOptionDto(PlacementPost.NONE.name, PlacementPost.NONE.label, true, null, null),
            ))
        } catch (_: IllegalArgumentException) { PostsResponse("UNAVAILABLE") }
    }

    fun policies(actorId: Int, snapshot: DomesticSnapshot): PoliciesResponse {
        val state = snapshot.state ?: return PoliciesResponse(snapshot.failure ?: "UNAVAILABLE")
        val actor = state.person(actorId) ?: return PoliciesResponse("UNAVAILABLE")
        return try {
            val ruler = DomesticRules.rulerOf(actor.nationId, state)?.id == actorId
            val counties = state.counties.filter { it.nationId > 0 && it.nationId == actor.nationId &&
                (ruler || actorId in DomesticRules.countyControllers(it, state)) }.map { county ->
                val stored = CountyPolicyState.read(county.meta)
                val effective = DomesticRules.effectivePolicy(county, state, design)
                val check = DomesticRules.assessPolicy(PolicyRequest(actorId, PolicyTarget.County(county.id),
                    probePolicy(stored?.slot)), state)
                CountyPolicyDto(county.id, snapshot.countyNames[county.id] ?: county.name, county.commanderyId,
                    county.commanderyId?.let { snapshot.commanderyNames[it] ?: it },
                    stored?.slot?.active?.let(::setting), stored?.slot?.pending?.let(::order),
                    EffectivePolicyDto(effective.policy.name, effective.policy.label, effective.source.name),
                    effective.seat?.let { seat -> SeatDto(seat.personId, state.person(seat.personId)?.name ?: "", seat.placed) },
                    stored?.lastApplied?.let { PolicyApplicationDto(it.at, it.policy, CountyPolicy.valueOf(it.policy).label, it.seat, it.result) },
                    check is DomesticAssessment.Eligible, (check as? DomesticAssessment.Rejected)?.reason?.let { ReasonDto(it.name, it.message) })
            }
            val nation = state.nation(actor.nationId)
            val commanderyPolicies = nation?.let { CommanderyPolicies.read(it.meta) }
            val commanderies = if (!ruler) emptyList() else state.counties.filter { it.nationId == actor.nationId && it.commanderyId != null }
                .groupBy { it.commanderyId!! }.toSortedMap().map { (id, members) ->
                    val slot = commanderyPolicies?.get(id)?.slot
                    val check = DomesticRules.assessPolicy(PolicyRequest(actorId, PolicyTarget.Commandery(id), probePolicy(slot)), state)
                    CommanderyPolicyDto(id, snapshot.commanderyNames[id], members.map { it.id }.sorted(),
                        slot?.active?.let(::setting), slot?.pending?.let(::order), check is DomesticAssessment.Eligible,
                        (check as? DomesticAssessment.Rejected)?.reason?.let { ReasonDto(it.name, it.message) })
                }
            val corpsPolicies = CorpsPolicyAssignments.read(actor.meta)
            val corps = DomesticRules.deployedCorps(state).filter { it.ownerGeneralId == actorId }.map { deployed ->
                val slot = corpsPolicies?.forOrder(deployed.orderId)?.slot
                CorpsPolicyDto(deployed.orderId, deployed.commanderGeneralId, state.person(deployed.commanderGeneralId)?.name,
                    slot?.active?.let(::setting), slot?.pending?.let(::order), true, null)
            }
            PoliciesResponse("READY", now = state.now,
                countyOptions = CountyPolicy.entries.map { CodeLabel(it.name, it.label) },
                corpsOptions = CorpsPolicy.entries.map { CodeLabel(it.name, it.label) },
                defaultPolicy = CodeLabel(design.defaultCountyPolicy.name, design.defaultCountyPolicy.label),
                provisional = design.status, counties = counties, commanderies = commanderies, corps = corps)
        } catch (_: IllegalArgumentException) { PoliciesResponse("UNAVAILABLE") }
    }

    fun works(actorId: Int, snapshot: DomesticSnapshot): WorksResponse {
        val state = snapshot.state ?: return WorksResponse(snapshot.failure ?: "UNAVAILABLE")
        val actor = state.person(actorId) ?: return WorksResponse("UNAVAILABLE")
        return try {
            val ruler = DomesticRules.rulerOf(actor.nationId, state)?.id == actorId
            val counties = state.counties.filter { it.nationId > 0 && it.nationId == actor.nationId &&
                (ruler || actorId in DomesticRules.countyControllers(it, state)) }.map { county ->
                val works = CountyWorks.read(county.meta)
                val seat = DomesticRules.seatedMagistrate(county, state)?.let { seat ->
                    val person = state.person(seat.personId)!!
                    SeatStats(person.leadership, person.strength, person.intelligence, person.politics, person.charm, false)
                }
                val active = works?.active?.let { work ->
                    val remaining = work.cost.debit(work.charged) ?: Resources()
                    ActiveWorkDto(work.work.name, work.work.label, work.requestedAt, work.progress, work.required,
                        (work.progress.toLong() * 100 / work.required).toInt(), DomesticEffects.remainingPhases(design, work, seat),
                        stock(work.cost), stock(work.charged), stock(remaining), work.lastProgressAt, work.stopReason,
                        work.stopReason?.let(::stopText), work.requestedAt >= state.now)
                }
                val startable = DomesticWork.entries.map { kind ->
                    val spec = design.works.getValue(kind)
                    val check = DomesticRules.assessWork(WorkRequest(actorId, county.id, kind), state)
                    val preview = DomesticEffects.newWork(design, kind, "preview", actorId, state.now)
                    StartableWorkDto(kind.name, kind.label, check is DomesticAssessment.Eligible,
                        (check as? DomesticAssessment.Rejected)?.reason?.let { ReasonDto(it.name, it.message) },
                        stock(spec.cost), spec.requiredProgress, DomesticEffects.remainingPhases(design, preview, seat))
                }
                CountyWorksDto(county.id, snapshot.countyNames[county.id] ?: county.name,
                    county.commanderyId?.let { snapshot.commanderyNames[it] ?: it }, snapshot.warehouseStocks[county.id]?.let(::stock),
                    active, works?.completed.orEmpty().map { CompletedWorkDto(it.work.name, it.work.label, it.completedAt) }, startable)
            }
            WorksResponse("READY", now = state.now, provisional = design.status, counties = counties)
        } catch (_: IllegalArgumentException) { WorksResponse("UNAVAILABLE") }
    }

    /** 권한만 보려고 지금과 다른 방침으로 판정한다(UNCHANGED·NOTHING_TO_CLEAR 를 피한다). */
    private fun probePolicy(slot: PolicySlot?): String =
        CountyPolicy.entries.first { it.name != slot?.active?.policy && it.name != slot?.pending?.policy }.name

    private fun setting(value: PolicySetting) = PolicySettingDto(value.policy, label(value.policy), value.since)
    private fun order(value: PolicyOrder) = PolicyOrderDto(value.policy, value.policy?.let(::label), value.requestedAt)
    private fun label(code: String): String = CountyPolicy.entries.firstOrNull { it.name == code }?.label
        ?: CorpsPolicy.entries.firstOrNull { it.name == code }?.label ?: code
    private fun stock(value: Resources) = StockDto(value.money, value.grain, value.iron, value.timber, value.horses)
    private fun stopText(code: String) = when (code) {
        DomesticEffects.INSUFFICIENT_STOCK -> "창고의 자재가 모자랍니다."
        "WAREHOUSE_NOT_READY" -> "현의 창고를 확인할 수 없습니다."
        "STALE_WAREHOUSE" -> "창고 정산이 어긋났습니다."
        else -> code
    }

    private fun target(target: PlacementTarget, snapshot: DomesticSnapshot): PlacementTargetDto = when (target) {
        is PlacementTarget.County -> PlacementTargetDto(countyId = target.countyId, label = snapshot.countyNames[target.countyId])
        is PlacementTarget.Province -> PlacementTargetDto(provinceId = target.provinceId)
        is PlacementTarget.Nation -> PlacementTargetDto(nationId = target.nationId,
            label = snapshot.state?.nation(target.nationId)?.name)
        PlacementTarget.None -> PlacementTargetDto()
    }
}
