package opensamguk.engine.campaign

import opensamguk.engine.siege.RoadFortPassage

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*
import opensamguk.logic.war.CampaignBalance
import opensamguk.logic.world.*
import org.slf4j.LoggerFactory

/**
 * 군단 군량 — 출병 적재와 보급선(2026-09-23 사용자 결정, 확정 `campaign-balance-v1.json` rations).
 *
 * - **출병 적재**: 출병하는 순간 출발지 창고망의 곡으로 부곡 휴대 군량을 (병력 × [CampaignBalance.DEPLOY_LOAD_MONTHS] 개월)까지 채운다.
 *   출발지가 자국 縣이 아니면(적지·무주지) 싣지 못한다. 창고가 모자라면 있는 만큼만 싣는다.
 * - **보급선**: 월 경계에 자국 縣 밖에 있는 군단으로 (병력 × [CampaignBalance.CONVOY_TARGET_MONTHS] 개월)까지 모자란 곡을 보낸다.
 *   가장 가까운(행군 경로 비용) 보급된 자국 縣에서 출발하고, 경로 비용 ÷ 한 순 행군 예산(올림, 최소 1)순 뒤 도착한다. **손실은 없다**
 *   (지연만 — spec §9.2 「멀수록 늦게 도착한다」). 경로가 적 군단이 있는 省을 지나면 그 달은 보내지 못한다(보급선 차단).
 *   값은 출발할 때 창고망에서 빠지고, 도착하는 순 경계에 부곡이 남아 있으면 휴대 군량에 더한다.
 *
 * 수송 중인 곡은 [CONVOYS_KEY] 에 담는다(도착 순이 지나면 비운다). 도장 [STAMP_KEY] 로 한 달에 한 번만 보낸다.
 */
class CorpsRations(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
) {
    data class Convoy(val bugokId: Int, val nationId: Int, val provisions: Long, val arrive: Phase) {
        fun toMetaValue(): Map<String, Any> = linkedMapOf("bugokId" to bugokId, "nationId" to nationId,
            "provisions" to provisions, "arrive" to arrive.toMetaValue())
    }

    private fun now() = world.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }

    /** 출병 적재. @return 실은 휴대 군량 합. */
    fun load(corps: DeployedCorps): Long {
        if (world.ruleProfile != RuleProfile.HWIHA) return 0
        val here = cityAt(world, corps.commanderGeneralId) ?: return 0
        val network = WarehouseNetwork(world, recorder)
        val counties = network.countiesFor(corps.nationId, here)
        if (counties.isEmpty()) return 0
        var loaded = 0L
        for (id in corps.bugokIds.sorted()) {
            val unit = world.getBugokById(id) ?: continue
            val want = (unit.troops.toLong() * CampaignBalance.DEPLOY_LOAD_MONTHS)
                .coerceAtMost(Int.MAX_VALUE.toLong()) - unit.provisions
            if (want <= 0) continue
            val add = minOf(want, network.grainIn(counties) / CampaignBalance.GRAIN_PER_PROVISION)
            if (add <= 0) break
            if (!network.payGrain(corps.nationId, counties, add * CampaignBalance.GRAIN_PER_PROVISION)) {
                log.warn("hwiha_corps_load_skipped commander={} unit={} reason=GRAIN_DEBIT_REJECTED", corps.commanderGeneralId, id)
                break
            }
            world.updateBugok(unit.copy(provisions = (unit.provisions + add).toInt()))
            loaded += add
        }
        return loaded
    }

    /** 월 경계 보급선 출발. @return 보낸 수송 건수(부곡 단위), HWIHA 가 아니면 null. */
    fun dispatch(year: Int, month: Int): Int? {
        if (world.ruleProfile != RuleProfile.HWIHA) return null
        val stamp = "%04d-%02d".format(year, month)
        if (world.getState().meta[STAMP_KEY] == stamp) return 0
        val projection = DeploymentExecutor(world, recorder, topology, metrics).projection()
        val edges = try { LandPassageState.read(world.getState().meta, topology) } catch (_: IllegalArgumentException) { null }
        val inFlight = convoys().toMutableList()
        var sent = 0
        if (projection != null && edges != null) {
            val wars = world.listDiplomacy().filter { it.state == 0 }.mapTo(hashSetOf()) { it.fromNationId to it.toNationId }
            val network = WarehouseNetwork(world, recorder)
            for (corps in projection.deployed.sortedBy { it.orderId }) {
                val nationEdges = RoadFortPassage.forNation(world, edges, corps.nationId) ?: continue
                // 자국 縣에 있으면 월 보충(UnitResupply)이 맡는다.
                val here = cityAt(world, corps.commanderGeneralId)
                if (here != null && world.getCityById(here)?.nationId == corps.nationId) continue
                val target = world.positionOf(corps.commanderGeneralId) as? StrategicNodeRef.LandProvince ?: continue
                val blocked = (MilitaryPresence.assessNation(corps.nationId, projection, wars)
                    as? MilitaryPresenceAssessment.Ready)?.blockedProvinceIds ?: continue
                val source = world.administrativeCountyIds.sorted().mapNotNull { countyId ->
                    val city = world.getCityById(countyId)?.takeIf { it.nationId == corps.nationId && it.supplyState != 0 }
                        ?: return@mapNotNull null
                    val node = world.landNodeOfCity(city.id) as? StrategicNodeRef.LandProvince ?: return@mapNotNull null
                    val path = (StrategicPathResolver.resolveLandMarch(topology, StrategicPathRequest(node, target, 1), nationEdges, metrics)
                        as? LandMarchPathResult.Resolved)?.path ?: return@mapNotNull null
                    // 보급선 차단: 도착 省 앞의 경로가 적 군단이 있는 省을 지나면 보내지 못한다.
                    if (path.nodeKeys.dropLast(1).any { it.removePrefix("land:") in blocked }) return@mapNotNull null
                    Triple(path.totalCostMm, city.id, path)
                }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: continue
                val counties = network.countiesFor(corps.nationId, source.second)
                val delay = maxOf(1L, (source.first + LandMarchMetricSnapshot.NORMAL_BUDGET_MM - 1) / LandMarchMetricSnapshot.NORMAL_BUDGET_MM)
                if (delay > Int.MAX_VALUE) {
                    log.warn("hwiha_convoy_skipped commander={} reason=ROUTE_TOO_LONG", corps.commanderGeneralId)
                    continue
                }
                val arrive = now().plus(delay.toInt())
                for (id in corps.bugokIds.sorted()) {
                    val unit = world.getBugokById(id) ?: continue
                    val coming = inFlight.filter { it.bugokId == id }.sumOf { it.provisions }
                    val want = (unit.troops.toLong() * CampaignBalance.CONVOY_TARGET_MONTHS)
                        .coerceAtMost(Int.MAX_VALUE.toLong()) - unit.provisions - coming
                    if (want <= 0) continue
                    val add = minOf(want, network.grainIn(counties) / CampaignBalance.GRAIN_PER_PROVISION)
                    if (add <= 0) break
                    if (!network.payGrain(corps.nationId, counties, add * CampaignBalance.GRAIN_PER_PROVISION)) {
                        log.warn("hwiha_convoy_skipped commander={} unit={} reason=GRAIN_DEBIT_REJECTED", corps.commanderGeneralId, id)
                        break
                    }
                    inFlight += Convoy(id, corps.nationId, add, arrive)
                    sent++
                }
            }
        }
        save(inFlight)
        world.setGameEnvValue(STAMP_KEY, stamp)
        recorder.recordKv("game_env", "game_env", STAMP_KEY, stamp)
        return sent
    }

    /** 순 경계 보급선 도착. @return 도착한 수송 건수. */
    fun deliver(): Int {
        if (world.ruleProfile != RuleProfile.HWIHA) return 0
        val all = convoys()
        if (all.isEmpty()) return 0
        val now = now()
        val (arrived, pending) = all.partition { it.arrive <= now }
        for (convoy in arrived) {
            // 부곡이 사라졌으면(궤멸·해산) 그 곡도 사라진다 — 되돌리는 규칙은 없다.
            val unit = world.getBugokById(convoy.bugokId) ?: continue
            val replenished = unit.provisions.toLong() + convoy.provisions
            if (replenished > Int.MAX_VALUE)
                log.warn("hwiha_convoy_delivery_capped unit={} excess={}", convoy.bugokId, replenished - Int.MAX_VALUE)
            world.updateBugok(unit.copy(provisions = replenished.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        }
        if (arrived.isNotEmpty()) save(pending)
        return arrived.size
    }

    fun convoys(): List<Convoy> = (world.getState().meta[CONVOYS_KEY] as? List<*>).orEmpty().mapNotNull { raw ->
        val m = raw as? Map<*, *> ?: return@mapNotNull null
        Convoy((m["bugokId"] as? Number)?.toInt() ?: return@mapNotNull null, (m["nationId"] as? Number)?.toInt() ?: 0,
            (m["provisions"] as? Number)?.toLong() ?: return@mapNotNull null,
            try { Phase.read(m["arrive"]) } catch (_: IllegalArgumentException) { return@mapNotNull null })
    }

    private fun save(convoys: List<Convoy>) {
        val value = convoys.sortedWith(compareBy({ it.arrive }, { it.bugokId })).map { it.toMetaValue() }
        if (world.getState().meta[CONVOYS_KEY] == value) return
        world.setGameEnvValue(CONVOYS_KEY, value)
        recorder.recordKv("game_env", "game_env", CONVOYS_KEY, value)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CorpsRations::class.java)
        const val STAMP_KEY = "supplyConvoyMonth"
        const val CONVOYS_KEY = "supplyConvoys"

        /** 장수가 실제로 선 省의 城(위치 정본). 기준 城이 그 省이면 그것을, 아니면 그 省의 城을, 城 없는 省이면 null. */
        fun cityAt(world: InMemoryTurnWorld, generalId: Int): Int? {
            val node = world.positionOf(generalId) ?: return null
            val reference = world.getGeneralById(generalId)?.cityId
            if (reference != null && world.landNodeOfCity(reference) == node) return reference
            return world.cityOfLandNode(node)
        }
    }
}
