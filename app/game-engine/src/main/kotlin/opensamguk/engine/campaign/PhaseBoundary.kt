package opensamguk.engine.campaign

import opensamguk.engine.siege.RoadFortPassage
import opensamguk.engine.siege.RoadFortSiegeService

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.input.LandPassageState
import opensamguk.logic.world.*
import org.slf4j.LoggerFactory

/**
 * HWIHA 순 경계(세계 처리, 재설계 spec §5.2). [opensamguk.engine.run.TurnRunService] 가 순마다(월 경계 포함)
 * 세계 날짜를 새 순으로 옮긴 직후 한 번 부른다. 월 경계 전용 단계(징세·녹봉·월단평)는 호출부가 이 뒤에 잇는다.
 *
 * 1. **포위**(§5.2 2단계) — 성 안 군량·사기·항복. 함락이 여기서 일어난다.
 * 2. **보급망 재계산**(§5.2 1단계의 재계산 부분) — 세력별 수도에서 보급 BFS 를 다시 돌려 縣의 보급 여부만 고친다.
 *    감쇠·중립화는 기존 월간 `UpdateCitySupply` 가 하고 여기서는 하지 않는다. 휘하 월드에서는 점령군 수비대가
 *    남은 고립 縣의 중립화만 억제한다. 포위 중인 縣은 외부 보급이 끊긴다
 *    (armyEncirclement.maintenance). 포위보다 **뒤**에 두는 것은 같은 순에 함락된 縣이 새 주인의 망에 들어가야
 *    같은 순 월세입이 새 주인에게 가기 때문이다(captureSettlement.monthlyTax = OWNER_AFTER_CAPTURE).
 *
 * 0. **보급선 도착** — 월 경계에 떠난 군량([CorpsRations])이 도착 순에 부곡에 실린다.
 *
 * 연결 창고 간 자동 이동·보급 단절 병력 감소(§5.2 1단계의 나머지)는 아직 없다.
 */
class PhaseBoundary(
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val cells: HanProvinceCellIndex,
    private val spatialSupplyNetwork: () -> SpatialSupplyNetwork? = { null },
    private val outcomes: WarOutcomeListener = WarOutcomeListener.NONE,
) {
    fun run(world: InMemoryTurnWorld, recorder: ChangeRecorder) {
        if (world.ruleProfile != RuleProfile.HWIHA) return
        // 0. 보급선 도착 — 포위 급식 판정 전에 도착한 군량을 싣는다.
        CorpsRations(world, recorder, topology, metrics).deliver()
        val siege = SiegeService(world, recorder, topology, metrics, cells, outcomes)
        siege.settleBoundary()
        RoadFortSiegeService(world, recorder, topology, metrics).settleBoundary()
        recomputeSupply(world, recorder, siege.besiegedCountyIds())
    }

    /** 월 경계 보급선 출발(녹봉·보충 뒤). 경로·지연 계산에 이 경계의 지형을 쓴다. */
    fun dispatchConvoys(world: InMemoryTurnWorld, recorder: ChangeRecorder, year: Int, month: Int): Int? =
        CorpsRations(world, recorder, topology, metrics).dispatch(year, month)

    /** @return 보급 여부가 바뀐 縣 수. 망을 계산할 수 없으면 기존 값을 그대로 두고 -1. */
    fun recomputeSupply(world: InMemoryTurnWorld, recorder: ChangeRecorder, besieged: Set<Int>): Int {
        val state = world.getState()
        val owned = world.listCities().filter { it.nationId != 0 }.sortedBy { it.id }
        val cities = owned.map { SupplyCity(it.id, it.nationId) }
        val capitals = world.listNations().filter { it.level > 0 }.sortedBy { it.id }
            .map { SupplyCapital(it.capitalCityId ?: 0, it.id) }
        // A boundary exception would wedge the turn loop forever; an unavailable network keeps last phase's flags.
        val supplied = try {
            val cityConst = ActiveWorldMap.requireVariant(state.config, state.meta, state.hanWorldVariant)
            val network = spatialSupplyNetwork()
            if (state.hanWorldVariant == HanWorldVariant.V3_1447_MAP4) {
                val spatial = requireNotNull(network) { "Map4 supply network is missing" }
                val strategic = requireNotNull(spatial.strategicSupply) { "Map4 strategic supply network is missing" }
                val passage = requireNotNull(LandPassageState.read(state.meta, topology)) {
                    "Map4 land passage state is missing"
                }
                val nations = owned.map { it.nationId }.toSet().sorted()
                val states = nations.associateWith { nationId ->
                    requireNotNull(RoadFortPassage.forNation(world, passage, nationId)) {
                        "Map4 road fort state is invalid"
                    }
                }
                computeSuppliedCitiesWithSpatialNetwork(cities, capitals, cityConst,
                    spatial.copy(strategicSupply = strategic.withEdgeStates(states)))
            } else if (network != null) computeSuppliedCitiesWithSpatialNetwork(cities, capitals, cityConst, network)
            else computeSuppliedCities(cities, capitals, cityConst)
        } catch (error: RuntimeException) {
            log.warn("hwiha_phase_supply_unavailable world={} reason={}", world.worldId.value, error.message)
            return -1
        }
        var changed = 0
        for (city in owned) {
            val next = if (city.id in supplied && city.id !in besieged) 1 else 0
            if (city.supplyState == next) continue
            val after = city.copy(supplyState = next)
            recorder.diffCity(PerTurnOverlay.toLogicCity(city), PerTurnOverlay.toLogicCity(after))
            world.applyCityDirtyFree(after)
            changed++
        }
        return changed
    }

    private companion object {
        val log = LoggerFactory.getLogger(PhaseBoundary::class.java)
    }
}
