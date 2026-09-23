package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.infra.seed.HwihaUnitProfilesJson
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.*
import opensamguk.logic.war.hwiha.*
import opensamguk.logic.world.*

/**
 * 縣城 공성(§5.1 6단계·§5.2 2단계). 상태는 V61 `hwiha_siege` 행이고 쓰기는 world dirty 집합 → flush 뿐이다.
 *
 * - **포위 시작**: 출전 군단이 목적지 省에 도착했고 그 省에 적대(교전 중이거나 무주) 縣治가 있으며 적 군단이 없으면
 *   개인 턴 이동 단계 뒤에 포위를 건다. 수비병이 0 이면 지킬 사람이 없어 바로 넘어간다(임시 규칙).
 * - **순 경계**: 포위 유지(급식 → 병력비 2배)를 보고, 성 안 수비병이 縣 창고 곡을 먹고([HwihaWarehouseSettlement]
 *   — 첫 실제 차감 호출자), 사기·항복을 정산한다. 유지 실패는 포위 해제, 급식 실패는 원정 종료까지다.
 * - **강공**: 개인 행동 `action.assault` — [HwihaSiegeAssault] 격자 전투.
 * - **항복 권고**: 개인 행동 `action.demandSurrender` — 성 안 사기·민심 문턱([HwihaSiegeRules.surrenderDemandAccepted]).
 * - **함락**: [HwihaCountyCapture] 로 縣 소유를 넘기고 창고는 縣에 남는다(통제만 넘어감). 같은 순 월세입은
 *   순 경계 순서(포위 → 징세)에 따라 새 주인에게 간다.
 */
class HwihaSiegeService(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val cells: HanProvinceCellIndex,
    private val outcomes: HwihaWarOutcomeListener = HwihaWarOutcomeListener.NONE,
) {
    enum class Failure(val message: String) {
        WRONG_RULE_PROFILE("이 세계에서는 공성 입력을 사용할 수 없습니다."),
        NOT_BESIEGING("포위 중인 縣이 없습니다."),
        STATE_UNAVAILABLE("포위 상태를 확인할 수 없습니다."),
        BATTLEFIELD_UNAVAILABLE("이 縣의 전장을 만들 수 없어 강공할 수 없습니다."),
        UNIT_UNAVAILABLE("강공에 쓸 수 있는 병종이 아닌 부대가 있습니다."),
        REFUSED("성 안의 사기와 민심이 아직 높아 항복 권고를 거절했습니다."),
    }

    private fun now() = world.getState().let { HwihaPhase(it.currentYear, it.currentMonth, it.currentPhase) }
    private fun projection() = HwihaDeploymentExecutor(world, recorder, topology, metrics).projection()
    private fun wars() = world.listDiplomacy().filter { it.state == 0 }.mapTo(hashSetOf()) { it.fromNationId to it.toNationId }
    private fun hostile(corpsNation: Int, cityNation: Int) = corpsNation > 0 && cityNation != corpsNation &&
        (cityNation == 0 || (corpsNation to cityNation) in wars() || (cityNation to corpsNation) in wars())

    /** 縣治가 이 省에 있는 행정 縣들(id 오름차순). */
    private fun countiesIn(node: StrategicNodeRef): List<Int> =
        world.administrativeCountyIds.filter { world.landNodeOfCity(it) == node }.sorted()

    private fun garrisonOf(city: City) = city.defence.coerceAtLeast(0)

    private fun corpsOf(commanderId: Int): HwihaDeployedCorps? =
        projection()?.deployed?.singleOrNull { it.commanderGeneralId == commanderId }

    private fun activeSiegeOf(commanderId: Int): HwihaSiege? =
        world.listHwihaSieges().singleOrNull { it.status == ACTIVE && it.besiegerGeneralId == commanderId }

    private fun corpsTroops(corps: HwihaDeployedCorps) = corps.bugokIds.sumOf { world.getBugokById(it)?.troops ?: 0 }

    // ── 포위 시작(개인 턴 6단계) ────────────────────────────────────────────
    /** @return 이번 턴에 포위를 걸었거나(또는 바로 넘어갔거나) 이미 포위 중이면 true. */
    fun startIfArrived(commanderId: Int): Boolean {
        if (world.ruleProfile != RuleProfile.HWIHA) return false
        if (activeSiegeOf(commanderId) != null) return true
        val corps = corpsOf(commanderId) ?: return false
        val commander = world.getGeneralById(commanderId) ?: return false
        val march = try { HwihaCorpsMarchState.read(commander.meta, topology, metrics) } catch (_: IllegalArgumentException) { null }
            ?: return false
        if (march.checkpoint.stop != LandMarchStop.ARRIVED) return false
        val node = world.positionOf(commanderId) as? StrategicNodeRef.LandProvince ?: return false
        val county = countiesIn(node).firstOrNull { id -> world.getCityById(id)?.let { hostile(corps.nationId, it.nationId) } == true }
            ?: return false
        val presence = HwihaMilitaryPresenceProvider(world, topology, metrics).assess(commanderId)
        if (presence !is MilitaryPresenceAssessment.Ready) return false
        if (node.id in presence.blockedProvinceIds) {
            log(commanderId, "縣城 앞에 적 군단이 있어 포위를 걸 수 없습니다.")
            return false
        }
        val existing = world.getHwihaSiege(county)
        if (existing?.status == ACTIVE) {
            log(commanderId, "이미 다른 군단이 이 縣城을 포위하고 있습니다.")
            return false
        }
        val city = checkNotNull(world.getCityById(county))
        val path = march.checkpoint.path
        val approach = if (path.nodeKeys.size >= 2) path.nodeKeys[path.nodeKeys.size - 2].removePrefix("land:")
            else fallbackApproach(node)
        val now = now()
        val siege = HwihaSiege(county, ACTIVE, commanderId, corps.ownerGeneralId, corps.orderId, corps.nationId,
            city.nationId, approach, now.year, now.month, now.phase, morale = HwihaSiegeMorale.INITIAL_MORALE,
            garrison = garrisonOf(city), timeline = listOf(entry(now, "START", HwihaSiegeMorale.INITIAL_MORALE, garrisonOf(city))))
        world.putHwihaSiege(siege)
        log(commanderId, "${city.name} 縣城을 포위했습니다.")
        if (garrisonOf(city) == 0) capture(siege, "UNDEFENDED")
        return true
    }

    private fun fallbackApproach(node: StrategicNodeRef.LandProvince): String {
        val neighbors = topology.traversalEdges.filter(LandMarchMetricSnapshot::supports).mapNotNull { edge ->
            when (node) { edge.from -> edge.to; edge.to -> if (edge.directed) null else edge.from; else -> null }
        }.filterIsInstance<StrategicNodeRef.LandProvince>().map { it.id }.distinct().sorted()
        return neighbors.firstOrNull { HwihaBattlefieldLayout.prepare(cells, node.id, it) is HwihaBattlefieldLayout.Result.Ready }
            ?: neighbors.firstOrNull() ?: ""
    }

    // ── NPC 공성 선택(개인 턴 6단계) ──────────────────────────────────────
    /** NPC 포위 지휘관: 항복 권고가 통하면 권고, 병력비가 충분하면 강공, 아니면 포위 유지. */
    fun npcAct(commanderId: Int) {
        val siege = activeSiegeOf(commanderId) ?: return
        val started = HwihaPhase(siege.startedYear, siege.startedMonth, siege.startedPhase)
        if (started >= now()) return // 포위를 건 턴에는 더 하지 않는다
        val city = world.getCityById(siege.countyId) ?: return
        if (HwihaSiegeRules.surrenderDemandAccepted(siege.morale, trustOf(city))) { demandSurrender(commanderId); return }
        val corps = corpsOf(commanderId) ?: return
        if (corpsTroops(corps).toLong() >= garrisonOf(city).toLong() * HwihaS3Provisional.NPC_ASSAULT_MIN_RATIO) assault(commanderId)
    }

    // ── 항복 권고 ────────────────────────────────────────────────────────────
    fun demandSurrender(actorId: Int): Failure? {
        if (world.ruleProfile != RuleProfile.HWIHA) return Failure.WRONG_RULE_PROFILE
        val siege = activeSiegeOf(actorId) ?: return Failure.NOT_BESIEGING
        val city = world.getCityById(siege.countyId) ?: return Failure.STATE_UNAVAILABLE
        val accepted = HwihaSiegeRules.surrenderDemandAccepted(siege.morale, trustOf(city))
        val next = siege.copy(timeline = appendEntry(siege.timeline, entry(now(), if (accepted) "DEMAND_ACCEPTED" else "DEMAND_REFUSED",
            siege.morale, siege.garrison, "trust" to trustOf(city))))
        world.putHwihaSiege(next)
        if (!accepted) { log(actorId, "${city.name} 縣城이 항복 권고를 거절했습니다."); return Failure.REFUSED }
        log(actorId, "${city.name} 縣城이 항복 권고를 받아들였습니다.")
        capture(next, "SURRENDER_DEMAND")
        return null
    }

    // ── 강공 ────────────────────────────────────────────────────────────────
    fun assault(actorId: Int): Failure? {
        if (world.ruleProfile != RuleProfile.HWIHA) return Failure.WRONG_RULE_PROFILE
        val siege = activeSiegeOf(actorId) ?: return Failure.NOT_BESIEGING
        val corps = corpsOf(actorId)?.takeIf { it.orderId == siege.besiegerOrderId } ?: return Failure.STATE_UNAVAILABLE
        val city = world.getCityById(siege.countyId) ?: return Failure.STATE_UNAVAILABLE
        val node = world.landNodeOfCity(siege.countyId) as? StrategicNodeRef.LandProvince ?: return Failure.STATE_UNAVAILABLE
        val layout = (HwihaBattlefieldLayout.prepare(cells, node.id, siege.approachProvinceId)
            as? HwihaBattlefieldLayout.Result.Ready)?.layout ?: return Failure.BATTLEFIELD_UNAVAILABLE
        if (layout.defenderZone.isEmpty() || layout.attackerZone.isEmpty()) return Failure.BATTLEFIELD_UNAVAILABLE
        val profiles = HwihaUnitProfilesJson.loadDefault()
        val attackers = corps.bugokIds.sorted().map { id ->
            val unit = world.getBugokById(id) ?: return Failure.STATE_UNAVAILABLE
            val profile = profiles.find(unit.crewTypeId) ?: return Failure.UNIT_UNAVAILABLE
            HwihaSiegeAssault.Attacker(unit.id, unit.troops, unit.training, unit.morale, unit.fatigue, profile)
        }
        val leadership = world.getGeneralById(actorId)?.stats?.leadership ?: return Failure.STATE_UNAVAILABLE
        val wallBonus = if (city.wallMax <= 0) 0 else
            (city.wall.coerceIn(0, city.wallMax).toLong() * HwihaS3Provisional.ASSAULT_MAX_WALL_BONUS_PERCENT / city.wallMax).toInt()
        val result = HwihaSiegeAssault.resolve(layout, attackers, leadership, garrisonOf(city),
            (siege.morale / 100).coerceIn(0, 100), wallBonus)
        // Attacker losses; an annihilated unit row is removed (troops > 0 constraint) and leaves the corps.
        val destroyed = sortedSetOf<Int>()
        for (unit in result.attackers) {
            val live = world.getBugokById(unit.bugokId) ?: continue
            if (unit.troops == 0) { world.removeBugok(live.id); destroyed += live.id; continue }
            val next = live.copy(troops = unit.troops, morale = unit.morale, fatigue = unit.fatigue)
            if (next != live) world.updateBugok(next)
        }
        val afterCity = city.copy(defence = result.garrisonRemaining)
        recorder.diffCity(PerTurnOverlay.toLogicCity(city), PerTurnOverlay.toLogicCity(afterCity))
        world.applyCityDirtyFree(afterCity)
        val next = siege.copy(garrison = result.garrisonRemaining, timeline = appendEntry(siege.timeline,
            entry(now(), "ASSAULT_" + result.outcome.name, siege.morale, result.garrisonRemaining,
                "rounds" to result.rounds, "replayHash" to result.replayHash)))
        world.putHwihaSiege(next)
        val remaining = corps.bugokIds.filter { it !in destroyed }
        if (remaining.isEmpty()) {
            endDeployment(corps)
            lift(next, "BESIEGER_DESTROYED")
            log(actorId, "${city.name} 縣城 강공에서 부대를 모두 잃었습니다(${result.rounds}회차).")
            return null
        }
        if (destroyed.isNotEmpty()) trimCorps(corps, remaining)
        if (result.outcome == HwihaSiegeAssault.Outcome.CAPTURED) {
            log(actorId, "${city.name} 縣城을 강공으로 함락했습니다(${result.rounds}회차).")
            capture(next, "ASSAULT")
        } else {
            log(actorId, "${city.name} 縣城 강공이 물리쳐졌습니다(${result.rounds}회차).")
        }
        return null
    }

    // ── 순 경계(2단계) ───────────────────────────────────────────────────────
    /** 모든 진행 중 포위를 縣 id 순으로 정산한다. 같은 순을 두 번 정산하지 않는다(settled 도장). */
    fun settleBoundary() {
        if (world.ruleProfile != RuleProfile.HWIHA) return
        val now = now()
        for (siege in world.listHwihaSieges().filter { it.status == ACTIVE }.sortedBy { it.countyId }) {
            if (siege.settledYear != null && HwihaPhase(siege.settledYear, siege.settledMonth!!, siege.settledPhase!!) >= now) continue
            val started = HwihaPhase(siege.startedYear, siege.startedMonth, siege.startedPhase)
            if (started >= now) continue
            settleOne(siege, now)
        }
    }

    private fun settleOne(siege: HwihaSiege, now: HwihaPhase) {
        val city = world.getCityById(siege.countyId)
        val corps = corpsOf(siege.besiegerGeneralId)?.takeIf { it.orderId == siege.besiegerOrderId }
        val stamped = siege.copy(settledYear = now.year, settledMonth = now.month, settledPhase = now.phase)
        if (city == null || corps == null || city.nationId != siege.defenderNationId ||
            world.positionOf(siege.besiegerGeneralId) != world.landNodeOfCity(siege.countyId) ||
            !hostile(corps.nationId, city.nationId)) {
            lift(stamped, "BESIEGER_GONE"); return
        }
        val units = corps.bugokIds.mapNotNull { world.getBugokById(it) }
        val troops = units.sumOf { it.troops }
        val fed = units.all { HwihaSiegeRules.besiegerFed(it.troops, it.provisions) }
        val garrison = garrisonOf(city)
        when (HwihaSiegeRules.maintenance(troops, garrison, fed)) {
            HwihaSiegeRules.Maintenance.UNFED -> {
                // armyEncirclement.interruption: 군량 부족은 원정 명령까지 끝낸다. 병력·남은 군량은 보존한다.
                endDeployment(corps)
                lift(stamped, "BESIEGER_UNFED")
                log(siege.besiegerGeneralId, "군량이 떨어져 ${city.name} 포위를 풀고 원정을 멈췄습니다.")
                return
            }
            HwihaSiegeRules.Maintenance.INSUFFICIENT_RATIO -> {
                lift(stamped, "INSUFFICIENT_RATIO")
                log(siege.besiegerGeneralId, "병력이 모자라 ${city.name} 포위를 유지하지 못했습니다.")
                return
            }
            HwihaSiegeRules.Maintenance.MAINTAINED -> Unit
        }
        val warehouse = try { HwihaCountyWarehouse.read(city.meta, city.id) } catch (_: IllegalArgumentException) { null }
        val grain = warehouse?.stock?.grain ?: 0L
        val settled = HwihaSiegeRules.settleTurn(siege.morale, garrison, grain)
        if (warehouse != null && settled.rationServed > 0) {
            val result = HwihaWarehouseSettlement(world, recorder).settle(city.id, city.nationId, warehouse.revision,
                HwihaResources(grain = settled.rationServed))
            check(result == HwihaWarehouseSettlement.Result.APPLIED) { "Validated garrison ration debit was rejected: $result" }
        }
        val next = stamped.copy(turns = siege.turns + 1, morale = settled.morale, garrison = garrison,
            timeline = appendEntry(siege.timeline, entry(now, "TURN", settled.morale, garrison,
                "rationDemand" to settled.rationDemand, "rationServed" to settled.rationServed,
                "grainAfter" to grain - settled.rationServed, "besiegerTroops" to troops)))
        world.putHwihaSiege(next)
        if (settled.surrendered) {
            log(siege.besiegerGeneralId, "${city.name} 縣城이 굶주림 끝에 항복했습니다.")
            capture(next, "STARVED")
        }
    }

    /** 포위 중인 縣 id — 순 경계 보급 재계산 뒤 외부 보급을 끊는다(armyEncirclement.maintenance). */
    fun besiegedCountyIds(): Set<Int> = world.listHwihaSieges().filter { it.status == ACTIVE }.mapTo(sortedSetOf()) { it.countyId }

    // ── 함락·해제 ────────────────────────────────────────────────────────────
    private fun capture(siege: HwihaSiege, reason: String) {
        val before = checkNotNull(world.getCityById(siege.countyId))
        val previousOwner = before.nationId
        val settlement = HwihaCountyCapture.settle(HwihaCountyCapture.CountyBefore(before.id, before.nationId,
            before.population, garrisonOf(before)), siege.besiegerNationId)
        val after = before.copy(nationId = settlement.ownerNationId, population = settlement.population,
            defence = settlement.garrisonTroops, supplyState = 0, frontState = 0)
        recorder.diffCity(PerTurnOverlay.toLogicCity(before), PerTurnOverlay.toLogicCity(after))
        world.applyCityDirtyFree(after)
        val now = now()
        world.putHwihaSiege(siege.copy(status = FALLEN, endReason = reason, garrison = 0,
            timeline = appendEntry(siege.timeline, entry(now, "FALLEN", siege.morale, 0, "reason" to reason,
                "disarmedToCivilians" to settlement.disarmedToCivilians))))
        // The expedition achieved its objective: the corps disbands in the captured county.
        corpsOf(siege.besiegerGeneralId)?.takeIf { it.orderId == siege.besiegerOrderId }?.let(::endDeployment)
        // Exactly once, right after the ownership transfer.
        outcomes.onCountyCaptured(before.id, previousOwner, siege.besiegerNationId, listOf(siege.besiegerGeneralId))
        world.pushLog(LogEntryDraft(scope = "general", category = "action",
            text = "${before.name} 縣이 넘어왔습니다.", generalId = siege.besiegerGeneralId,
            nationId = siege.besiegerNationId))
    }

    private fun lift(siege: HwihaSiege, reason: String) {
        world.putHwihaSiege(siege.copy(status = LIFTED, endReason = reason,
            timeline = appendEntry(siege.timeline, entry(now(), "LIFTED", siege.morale, siege.garrison, "reason" to reason))))
    }

    private fun endDeployment(corps: HwihaDeployedCorps) {
        updateMeta(corps.ownerGeneralId) { meta ->
            val deployment = HwihaDeploymentState.read(meta) ?: return@updateMeta meta
            val rest = deployment.corps.filterNot { it.orderId == corps.orderId }
            if (rest.isEmpty()) meta - HwihaDeploymentState.META_KEY
            else meta + (HwihaDeploymentState.META_KEY to HwihaDeploymentState(rest).toMetaValue())
        }
        updateMeta(corps.commanderGeneralId) { meta -> meta - HwihaCorpsOrder.META_KEY - HwihaCorpsMarchState.META_KEY }
    }

    private fun trimCorps(corps: HwihaDeployedCorps, remaining: List<Int>) {
        updateMeta(corps.ownerGeneralId) { meta ->
            val deployment = HwihaDeploymentState.read(meta) ?: return@updateMeta meta
            meta + (HwihaDeploymentState.META_KEY to HwihaDeploymentState(deployment.corps.map {
                if (it.orderId == corps.orderId) it.copy(bugokIds = remaining.sorted()) else it
            }).toMetaValue())
        }
    }

    private fun updateMeta(generalId: Int, change: (Map<String, Any?>) -> Map<String, Any?>) {
        val before = world.getGeneralById(generalId) ?: return
        val meta = change(before.meta)
        if (meta == before.meta) return
        val after = before.copy(meta = meta)
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
    }

    private fun log(generalId: Int, text: String) = world.pushLog(LogEntryDraft(scope = "general", category = "action",
        text = text, generalId = generalId, nationId = world.getGeneralById(generalId)?.nationId))

    companion object {
        const val ACTIVE = "ACTIVE"
        const val LIFTED = "LIFTED"
        const val FALLEN = "FALLEN"

        fun trustOf(city: City): Double = (city.meta["trust"] as? Number)?.toDouble() ?: 0.0

        private fun entry(at: HwihaPhase, event: String, morale: Int, garrison: Int, vararg extra: Pair<String, Any?>) =
            linkedMapOf<String, Any?>("year" to at.year, "month" to at.month, "phase" to at.phase, "event" to event,
                "morale" to morale, "garrison" to garrison).apply { extra.forEach { put(it.first, it.second) } }

        private fun appendEntry(timeline: List<Map<String, Any?>>, entry: Map<String, Any?>) =
            (timeline + entry).takeLast(HwihaS3Provisional.SIEGE_TIMELINE_MAX)
    }
}
