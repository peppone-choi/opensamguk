package opensamguk.engine.campaign

import opensamguk.logic.vision.ScoutPosts

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCard
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticBugok
import opensamguk.logic.domestic.DomesticDiplomacy
import opensamguk.logic.domestic.DomesticProjection

import opensamguk.logic.domestic.DomesticDesign
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.input.*
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.StrategicNodeRef
import opensamguk.logic.world.StrategicTopologySnapshot
import opensamguk.logic.world.StrategicRoadGate
import opensamguk.logic.world.CityConstVariant
import opensamguk.infra.seed.UnitProfilesJson

/**
 * 휘하 내정 입력이 읽는 고정 자료. [geography] 가 없으면 郡 방침과 향당 보너스를 판정할 수 없고(STATE_UNAVAILABLE·보너스 없음),
 * [topology]·[metrics] 가 없으면 배치 부임 행군을 하지 않는다. [merit] 은 치적 사건을 받는 자리(기본은 버림).
 */
class DomesticContext(
    val design: DomesticDesign = DomesticDesign.CANON,
    val geography: CountyGeography? = null,
    val nativeCounties: NativeCountyLedger? = null,
    val topology: StrategicTopologySnapshot? = null,
    val metrics: LandMarchMetricSnapshot? = null,
    val roadGates: List<StrategicRoadGate> = emptyList(),
    val merit: GovernanceMeritSink = GovernanceMeritSink.NONE,
    val cityConst: CityConstVariant? = null,
) {
    private val supportedCrewTypeIds by lazy { UnitProfilesJson.loadDefault().profiles.map { it.crewTypeId }.toSet() }
    /** 현재 월드 상태의 공유 판정 투영(API 와 같은 규칙). */
    fun projection(world: InMemoryTurnWorld): DomesticProjection {
        val positions = world.generalPositionSnapshot()
        val state = world.getState()
        val generals = world.listGenerals().sortedBy { it.id }
        val geography = geography
        val ledger = nativeCounties
        return DomesticProjection(
            profile = world.ruleProfile,
            now = Phase(state.currentYear, state.currentMonth, state.currentPhase),
            people = generals.map { g ->
                val position = positions?.stateFor(g.id)
                DomesticPerson(g.id, g.name, g.nationId, (g.userId?.toLongOrNull() ?: 0) > 0, g.npcState, g.officerLevel,
                    g.stats.leadership, g.stats.strength, g.stats.intelligence, g.stats.politics, g.stats.charm,
                    (position?.node as? StrategicNodeRef.LandProvince)?.id, position?.battlefield != null, g.meta, g.injury,
                    g.gold, g.rice)
            },
            cards = world.listRetainers().sortedBy { it.id }.map { DomesticCard(it.id, it.masterGeneralId, it.generalId, it.relation, it.name) },
            counties = world.listCities().filter { it.id in world.administrativeCountyIds }.sortedBy { it.id }.map { c ->
                DomesticCounty(c.id, c.name, c.nationId, (world.landNodeOfCity(c.id) as? StrategicNodeRef.LandProvince)?.id,
                    geography?.commanderyOf(c.id), c.meta)
            },
            nations = world.listNations().sortedBy { it.id }.map { DomesticNation(it.id, it.name, it.capitalCityId, it.meta,
                it.level, it.gold, it.rice, it.tech, it.chiefGeneralId) },
            landProvinceIds = positions?.knownLandProvinceIds,
            bugoks = world.listBugoks().map { DomesticBugok(it.id, it.masterGeneralId, it.crewTypeId, it.training) },
            countyAdjacency = world.administrativeCountyIds.associateWith { countyId ->
                cityConst?.byId(countyId)?.path?.keys?.filter { it in world.administrativeCountyIds }?.toSet() ?: emptySet()
            },
            supportedCrewTypeIds = supportedCrewTypeIds,
            diplomacy = world.listDiplomacy().map { DomesticDiplomacy(it.fromNationId, it.toNationId, it.state, it.term) },
            homeCountyByGeneral = if (geography == null || ledger == null) emptyMap() else generals.mapNotNull { g ->
                ledger.homeCounty(g.name, g.meta, geography)?.let { g.id to it }
            }.toMap(),
            provinceIdsByCounty = if (geography == null) emptyMap() else world.administrativeCountyIds
                .associateWith(geography::provincesOfCounty),
            activeSiegeCountyIds = world.listSieges().filter { it.status == "ACTIVE" }.mapTo(hashSetOf()) { it.countyId },
        )
    }
}

internal fun InMemoryTurnWorld.phaseNow(): Phase = getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }

internal fun InMemoryTurnWorld.updateGeneralMeta(recorder: ChangeRecorder, before: TurnGeneral, meta: Map<String, Any?>) {
    if (before.meta == meta) return
    val after = before.copy(meta = meta)
    recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
    applyGeneralDirtyFree(after)
}

internal fun InMemoryTurnWorld.updateCityMeta(recorder: ChangeRecorder, cityId: Int, meta: Map<String, Any?>) {
    val before = checkNotNull(getCityById(cityId)) { "unknown city $cityId" }
    if (before.meta == meta) return
    val after = before.copy(meta = meta)
    recorder.diffCity(PerTurnOverlay.toLogicCity(before), PerTurnOverlay.toLogicCity(after))
    checkNotNull(applyCityDirtyFree(after))
}

internal fun InMemoryTurnWorld.updateNationMeta(recorder: ChangeRecorder, nationId: Int, meta: Map<String, Any?>) {
    val before = checkNotNull(getNationById(nationId)) { "unknown nation $nationId" }
    if (before.meta == meta) return
    val after = before.copy(meta = meta)
    recorder.diffNation(PerTurnOverlay.toLogicNation(before), PerTurnOverlay.toLogicNation(after))
    applyNationDirtyFree(after)
}

internal fun Map<String, Any?>.withKey(key: String, value: Any?): Map<String, Any?> =
    if (value == null) this - key else LinkedHashMap(this).apply { put(key, value) }

/** 정찰 배치의 공개 투영(주인 meta `scoutPosts`)을 정본 배치에서 다시 쓴다. 시야 스트림이 이 키를 읽는다. */
internal fun InMemoryTurnWorld.syncScoutPosts(recorder: ChangeRecorder, ownerId: Int) {
    val owner = getGeneralById(ownerId) ?: return
    val cards = listRetainers().map { DomesticCard(it.id, it.masterGeneralId, it.generalId, it.relation, it.name) }
    val projected = ScoutPosts.project(ownerId, cards) { getGeneralById(it)?.meta }
    updateGeneralMeta(recorder, owner, owner.meta.withKey(ScoutPosts.META_KEY, projected))
}
