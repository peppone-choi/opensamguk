package opensamguk.engine.campaign

import opensamguk.engine.turn.*
import opensamguk.infra.seed.UnitProfilesJson
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.*
import opensamguk.logic.war.*
import opensamguk.logic.world.*

/**
 * 縣城 공성(§5.1 6단계·§5.2 2단계). 상태는 V61 `siege` 행이고 쓰기는 world dirty 집합 → flush 뿐이다.
 *
 * - **포위 시작**: 출전 군단이 목적지 省에 도착했고 그 省에 적대(교전 중이거나 무주) 縣治가 있으며 적 군단이 없으면
 *   개인 턴 이동 단계 뒤에 포위를 건다. 수비병이 0 이면 지킬 사람이 없어 바로 넘어간다(2026-09-23 확정 규칙).
 * - **순 경계**: 포위 유지(급식 → 병력비 2배)를 보고, 성 안 수비병이 縣 창고 곡을 먹고([WarehouseSettlement]
 *   — 첫 실제 차감 호출자), 사기·항복을 정산한다. 유지 실패는 포위 해제, 급식 실패는 원정 종료까지다.
 * - **강공**: 개인 행동 `action.assault` — [SiegeAssault] 격자 전투.
 * - **항복 권고**: 개인 행동 `action.demandSurrender` — 성 안 사기·민심 문턱([SiegeRules.surrenderDemandAccepted]).
 * - **함락**: [CountyCapture] 로 縣 소유를 넘기고 창고는 縣에 남는다(통제만 넘어감). 같은 순 월세입은
 *   순 경계 순서(포위 → 징세)에 따라 새 주인에게 간다.
 */
class SiegeService(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val cells: HanProvinceCellIndex,
    private val outcomes: WarOutcomeListener = WarOutcomeListener.NONE,
) {
    enum class Failure(val message: String) {
        WRONG_RULE_PROFILE("이 세계에서는 공성 입력을 사용할 수 없습니다."),
        NOT_BESIEGING("포위 중인 縣이 없습니다."),
        STATE_UNAVAILABLE("포위 상태를 확인할 수 없습니다."),
        BATTLEFIELD_UNAVAILABLE("이 縣의 전장을 만들 수 없어 강공할 수 없습니다."),
        UNIT_UNAVAILABLE("강공에 쓸 수 있는 병종이 아닌 부대가 있습니다."),
        ASSAULT_NOT_READY("포위한 지 한 달(3순)이 지나야 강공할 수 있습니다."),
        REFUSED("성 안의 사기와 민심이 아직 높아 항복 권고를 거절했습니다."),
        BATTLE_PENDING("포위 군단이 조우 전투 중이라 공성 행동을 할 수 없습니다."),
    }

    private fun now() = world.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }
    private fun projection() = DeploymentExecutor(world, recorder, topology, metrics).projection()
    private fun wars() = world.listDiplomacy().filter { it.state == 0 }.mapTo(hashSetOf()) { it.fromNationId to it.toNationId }
    private fun hostile(corpsNation: Int, cityNation: Int) = corpsNation > 0 && cityNation != corpsNation &&
        (cityNation == 0 || (corpsNation to cityNation) in wars() || (cityNation to corpsNation) in wars())

    /** 縣治가 이 省에 있는 행정 縣들(id 오름차순). */
    private fun countiesIn(node: StrategicNodeRef): List<Int> =
        world.administrativeCountyIds.filter { world.landNodeOfCity(it) == node }.sorted()

    private fun garrisonOf(city: City) = CityMilitaryState.read(city.meta, city.defence.coerceAtLeast(0)).troops

    private fun withGarrison(city: City, troops: Int, resetCondition: Boolean = false): City {
        val military = if (resetCondition) CityMilitaryState.INITIAL
            else CityMilitaryState.read(city.meta, city.defence.coerceAtLeast(0))
        return city.copy(meta = city.meta + (CityMilitaryState.META_KEY to military.copy(troops = troops).toMetaValue()))
    }

    private fun corpsOf(commanderId: Int): DeployedCorps? =
        projection()?.deployed?.singleOrNull { it.commanderGeneralId == commanderId }

    private fun inBattle(commanderId: Int): Boolean =
        projection()?.people?.singleOrNull { it.id == commanderId }?.inBattle ?: true

    private fun activeSiegeOf(commanderId: Int): Siege? =
        world.listSieges().singleOrNull { it.status == ACTIVE && it.besiegerGeneralId == commanderId }

    private fun corpsTroops(corps: DeployedCorps) = corps.bugokIds.sumOf { world.getBugokById(it)?.troops ?: 0 }

    // ── 포위 시작(개인 턴 6단계) ────────────────────────────────────────────
    /** @return 이번 턴에 포위를 걸었거나(또는 바로 넘어갔거나) 이미 포위 중이면 true. */
    fun startIfArrived(commanderId: Int): Boolean {
        if (world.ruleProfile != RuleProfile.HWIHA) return false
        if (activeSiegeOf(commanderId) != null) return true
        val corps = corpsOf(commanderId) ?: return false
        val commander = world.getGeneralById(commanderId) ?: return false
        val march = try { CorpsMarchState.read(commander.meta, topology, metrics) } catch (_: IllegalArgumentException) { null }
            ?: return false
        if (march.checkpoint.stop != LandMarchStop.ARRIVED) return false
        val node = world.positionOf(commanderId) as? StrategicNodeRef.LandProvince ?: return false
        val county = countiesIn(node).firstOrNull { id -> world.getCityById(id)?.let { hostile(corps.nationId, it.nationId) } == true }
            ?: return false
        val presence = MilitaryPresenceProvider(world, topology, metrics).assess(commanderId)
        if (presence !is MilitaryPresenceAssessment.Ready) return false
        if (node.id in presence.blockedProvinceIds) {
            log(commanderId, "縣城 앞에 적 군단이 있어 포위를 걸 수 없습니다.")
            return false
        }
        val existing = world.getSiege(county)
        if (existing?.status == ACTIVE) {
            log(commanderId, "이미 다른 군단이 이 縣城을 포위하고 있습니다.")
            return false
        }
        val city = world.getCityById(county) ?: run {
            log(commanderId, "포위 대상 縣 자료가 없어 이번 출병을 건너뛰었습니다.")
            return false
        }
        val garrison = try { garrisonOf(city) } catch (_: IllegalArgumentException) {
            log(commanderId, "${city.name} 縣城의 도시 병력 상태를 확인할 수 없어 포위를 시작하지 않았습니다.")
            return false
        }
        // A siege that could not hold at the next boundary is not started (no start/lift churn every phase).
        val units = corps.bugokIds.mapNotNull { world.getBugokById(it) }
        if (garrison > 0 && SiegeRules.maintenance(units.sumOf { it.troops }, garrison,
                units.all { SiegeRules.besiegerFed(it.troops, it.provisions) }) != SiegeRules.Maintenance.MAINTAINED) return false
        val path = march.checkpoint.path
        val approach = if (path.nodeKeys.size >= 2) path.nodeKeys[path.nodeKeys.size - 2].removePrefix("land:")
            else fallbackApproach(node)
        val now = now()
        val siege = Siege(county, ACTIVE, commanderId, corps.ownerGeneralId, corps.orderId, corps.nationId,
            city.nationId, approach, now.year, now.month, now.phase, morale = SiegeMorale.INITIAL_MORALE,
            garrison = garrison, timeline = listOf(entry(now, "START", SiegeMorale.INITIAL_MORALE, garrison)))
        world.putSiege(siege)
        log(commanderId, "${city.name} 縣城을 포위했습니다.")
        if (garrison == 0) capture(siege, "UNDEFENDED")
        return true
    }

    private fun fallbackApproach(node: StrategicNodeRef.LandProvince): String {
        val neighbors = topology.traversalEdges.filter(LandMarchMetricSnapshot::supports).mapNotNull { edge ->
            when (node) { edge.from -> edge.to; edge.to -> if (edge.directed) null else edge.from; else -> null }
        }.filterIsInstance<StrategicNodeRef.LandProvince>().map { it.id }.distinct().sorted()
        return neighbors.firstOrNull { BattlefieldLayout.prepare(cells, node.id, it) is BattlefieldLayout.Result.Ready }
            ?: neighbors.firstOrNull() ?: ""
    }

    // ── NPC 공성 선택(개인 턴 6단계) ──────────────────────────────────────
    /** NPC 포위 지휘관: 구원할 자국 縣이 있으면 포위를 풀고, 아니면 항복 권고가 통하면 권고, 강공 준비가 됐고 병력비가 충분하면 강공, 아니면 포위 유지. */
    fun npcAct(commanderId: Int) {
        val siege = activeSiegeOf(commanderId) ?: return
        if (inBattle(commanderId)) return
        val started = Phase(siege.startedYear, siege.startedMonth, siege.startedPhase)
        if (started >= now()) return // 포위를 건 턴에는 더 하지 않는다
        // 구원이 먼저다(포위 중에도): 자국 縣이 포위돼 있고 구원할 수 있으면 이 포위를 풀고 출병을 끝낸다.
        // 다음 턴 출병 선택기가 같은 규칙으로 구원 출병을 고른다.
        if (NpcDeploySelector(topology, metrics).reliefFor(world, commanderId) != null) {
            corpsOf(commanderId)?.takeIf { it.orderId == siege.besiegerOrderId }?.let(::endDeployment)
            lift(siege, "RELIEF")
            log(commanderId, "자국 縣을 구원하려고 ${world.getCityById(siege.countyId)?.name ?: ""} 縣城 포위를 풀었습니다.")
            return
        }
        val city = world.getCityById(siege.countyId) ?: return
        if (SiegeRules.surrenderDemandAccepted(siege.morale, trustOf(city))) { demandSurrender(commanderId); return }
        val corps = corpsOf(commanderId) ?: return
        if (siege.turns < CampaignBalance.ASSAULT_MIN_SIEGE_TURNS) return
        val garrison = try { garrisonOf(city) } catch (_: IllegalArgumentException) {
            lift(siege, "STATE_UNAVAILABLE"); return
        }
        if (corpsTroops(corps).toLong() >= garrison.toLong() * CampaignBalance.NPC_ASSAULT_MIN_RATIO) assault(commanderId)
    }

    /**
     * NPC 원정 마감: 군단이 목적지에 도착했는데 포위를 걸지 못했으면(굶음·병력 부족·이미 포위됨·자국 縣 귀환) 출병을 끝낸다.
     * 끝내지 않으면 도착한 군단이 그 자리에 영원히 서 있어 다음 출병·구원·보충이 모두 막힌다.
     * @return 출병을 끝냈으면 true.
     */
    fun npcEndIfStranded(commanderId: Int): Boolean {
        if (activeSiegeOf(commanderId) != null || inBattle(commanderId)) return false
        val corps = corpsOf(commanderId) ?: return false
        val commander = world.getGeneralById(commanderId) ?: return false
        val march = try { CorpsMarchState.read(commander.meta, topology, metrics) } catch (_: IllegalArgumentException) { null }
            ?: return false
        if (march.checkpoint.stop != LandMarchStop.ARRIVED) return false
        endDeployment(corps)
        log(commanderId, "목적지에서 포위를 걸 수 없어 원정을 마쳤습니다.")
        return true
    }

    // ── 항복 권고 ────────────────────────────────────────────────────────────
    fun demandSurrender(actorId: Int): Failure? {
        if (world.ruleProfile != RuleProfile.HWIHA) return Failure.WRONG_RULE_PROFILE
        val siege = activeSiegeOf(actorId) ?: return Failure.NOT_BESIEGING
        if (inBattle(actorId)) return Failure.BATTLE_PENDING
        val city = world.getCityById(siege.countyId) ?: return Failure.STATE_UNAVAILABLE
        val accepted = SiegeRules.surrenderDemandAccepted(siege.morale, trustOf(city))
        val next = siege.copy(timeline = appendEntry(siege.timeline, entry(now(), if (accepted) "DEMAND_ACCEPTED" else "DEMAND_REFUSED",
            siege.morale, siege.garrison, "trust" to trustOf(city))))
        world.putSiege(next)
        if (!accepted) { log(actorId, "${city.name} 縣城이 항복 권고를 거절했습니다."); return Failure.REFUSED }
        log(actorId, "${city.name} 縣城이 항복 권고를 받아들였습니다.")
        capture(next, "SURRENDER_DEMAND")
        return null
    }

    // ── 강공 ────────────────────────────────────────────────────────────────
    fun assault(actorId: Int): Failure? {
        if (world.ruleProfile != RuleProfile.HWIHA) return Failure.WRONG_RULE_PROFILE
        val siege = activeSiegeOf(actorId) ?: return Failure.NOT_BESIEGING
        if (inBattle(actorId)) return Failure.BATTLE_PENDING
        if (siege.turns < CampaignBalance.ASSAULT_MIN_SIEGE_TURNS) return Failure.ASSAULT_NOT_READY
        val corps = corpsOf(actorId)?.takeIf { it.orderId == siege.besiegerOrderId } ?: return Failure.STATE_UNAVAILABLE
        val city = world.getCityById(siege.countyId) ?: return Failure.STATE_UNAVAILABLE
        val node = world.landNodeOfCity(siege.countyId) as? StrategicNodeRef.LandProvince ?: return Failure.STATE_UNAVAILABLE
        val layout = (BattlefieldLayout.prepare(cells, node.id, siege.approachProvinceId)
            as? BattlefieldLayout.Result.Ready)?.layout ?: return Failure.BATTLEFIELD_UNAVAILABLE
        if (layout.defenderZone.isEmpty() || layout.attackerZone.isEmpty()) return Failure.BATTLEFIELD_UNAVAILABLE
        val profiles = UnitProfilesJson.loadDefault()
        val attackers = corps.bugokIds.sorted().map { id ->
            val unit = world.getBugokById(id) ?: return Failure.STATE_UNAVAILABLE
            val profile = profiles.find(unit.crewTypeId) ?: return Failure.UNIT_UNAVAILABLE
            SiegeAssault.Attacker(unit.id, unit.troops, unit.training, unit.morale, unit.fatigue, profile)
        }
        val leadership = world.getGeneralById(actorId)?.stats?.leadership ?: return Failure.STATE_UNAVAILABLE
        val wallBonus = if (city.wallMax <= 0) 0 else
            (city.wall.coerceIn(0, city.wallMax).toLong() * CampaignBalance.ASSAULT_MAX_WALL_BONUS_PERCENT / city.wallMax).toInt()
        val defenceBonus = if (city.defenceMax <= 0) 0 else
            (city.defence.coerceIn(0, city.defenceMax).toLong() * CampaignBalance.ASSAULT_MAX_DEFENCE_BONUS_PERCENT / city.defenceMax).toInt()
        val cityMilitary = try { CityMilitaryState.read(city.meta, city.defence.coerceAtLeast(0)) }
            catch (_: IllegalArgumentException) { return Failure.STATE_UNAVAILABLE }
        val result = SiegeAssault.resolve(layout, attackers, leadership, cityMilitary.troops,
            (siege.morale / 100 + cityMilitary.morale - CityMilitaryState.INITIAL.morale).coerceIn(0, 100),
            wallBonus, cityMilitary.training, defenceBonus)
        // Attacker losses; an annihilated unit row is removed (troops > 0 constraint) and leaves the corps.
        val destroyed = sortedSetOf<Int>()
        for (unit in result.attackers) {
            val live = world.getBugokById(unit.bugokId) ?: continue
            if (unit.troops == 0) { world.removeBugok(live.id); destroyed += live.id; continue }
            val next = live.copy(troops = unit.troops, morale = unit.morale, fatigue = unit.fatigue)
            if (next != live) world.updateBugok(next)
        }
        val afterCity = withGarrison(city, result.garrisonRemaining)
        recorder.diffCity(PerTurnOverlay.toLogicCity(city), PerTurnOverlay.toLogicCity(afterCity))
        world.applyCityDirtyFree(afterCity)
        val next = siege.copy(garrison = result.garrisonRemaining, timeline = appendEntry(siege.timeline,
            entry(now(), "ASSAULT_" + result.outcome.name, siege.morale, result.garrisonRemaining,
                "rounds" to result.rounds, "replayHash" to result.replayHash)))
        world.putSiege(next)
        val remaining = corps.bugokIds.filter { it !in destroyed }
        if (remaining.isEmpty()) {
            endDeployment(corps)
            lift(next, "BESIEGER_DESTROYED")
            log(actorId, "${city.name} 縣城 강공에서 부대를 모두 잃었습니다(${result.rounds}회차).")
            return null
        }
        if (destroyed.isNotEmpty()) trimCorps(corps, remaining)
        if (result.outcome == SiegeAssault.Outcome.CAPTURED) {
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
        for (siege in world.listSieges().filter { it.status == ACTIVE }.sortedBy { it.countyId }) {
            val settledYear = siege.settledYear
            val settledMonth = siege.settledMonth
            val settledPhase = siege.settledPhase
            if (settledYear != null && settledMonth != null && settledPhase != null &&
                Phase(settledYear, settledMonth, settledPhase) >= now) continue
            val started = Phase(siege.startedYear, siege.startedMonth, siege.startedPhase)
            if (started >= now) continue
            settleOne(siege, now)
        }
    }

    private fun settleOne(siege: Siege, now: Phase) {
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
        val fed = units.all { SiegeRules.besiegerFed(it.troops, it.provisions) }
        val garrison = try { garrisonOf(city) } catch (_: IllegalArgumentException) {
            lift(stamped, "STATE_UNAVAILABLE"); return
        }
        when (SiegeRules.maintenance(troops, garrison, fed)) {
            SiegeRules.Maintenance.UNFED -> {
                // armyEncirclement.interruption: 군량 부족은 원정 명령까지 끝낸다. 병력·남은 군량은 보존한다.
                endDeployment(corps)
                lift(stamped, "BESIEGER_UNFED")
                log(siege.besiegerGeneralId, "군량이 떨어져 ${city.name} 포위를 풀고 원정을 멈췄습니다.")
                return
            }
            SiegeRules.Maintenance.INSUFFICIENT_RATIO -> {
                lift(stamped, "INSUFFICIENT_RATIO")
                log(siege.besiegerGeneralId, "병력이 모자라 ${city.name} 포위를 유지하지 못했습니다.")
                return
            }
            SiegeRules.Maintenance.MAINTAINED -> Unit
        }
        val warehouse = try { CountyWarehouse.read(city.meta, city.id) } catch (_: IllegalArgumentException) { null }
        val grain = warehouse?.stock?.grain ?: 0L
        val settled = SiegeRules.settleTurn(siege.morale, garrison, grain)
        if (warehouse != null && settled.rationServed > 0) {
            val result = WarehouseSettlement(world, recorder).settle(city.id, city.nationId, warehouse.revision,
                Resources(grain = settled.rationServed))
            if (result != WarehouseSettlement.Result.APPLIED) {
                lift(stamped, "GARRISON_RATION_UNAVAILABLE")
                log(siege.besiegerGeneralId, "${city.name} 縣城의 군량 정산에 실패해 포위를 풀었습니다($result).")
                return
            }
        }
        val next = stamped.copy(turns = siege.turns + 1, morale = settled.morale, garrison = garrison,
            timeline = appendEntry(siege.timeline, entry(now, "TURN", settled.morale, garrison,
                "rationDemand" to settled.rationDemand, "rationServed" to settled.rationServed,
                "grainAfter" to grain - settled.rationServed, "besiegerTroops" to troops)))
        world.putSiege(next)
        if (settled.surrendered) {
            log(siege.besiegerGeneralId, "${city.name} 縣城이 굶주림 끝에 항복했습니다.")
            capture(next, "STARVED")
        }
    }

    /** 포위 중인 縣 id — 순 경계 보급 재계산 뒤 외부 보급을 끊는다(armyEncirclement.maintenance). */
    fun besiegedCountyIds(): Set<Int> = world.listSieges().filter { it.status == ACTIVE }.mapTo(sortedSetOf()) { it.countyId }

    // ── 함락·해제 ────────────────────────────────────────────────────────────
    private fun capture(siege: Siege, reason: String) {
        val before = world.getCityById(siege.countyId) ?: run {
            lift(siege, "COUNTY_UNAVAILABLE")
            log(siege.besiegerGeneralId, "함락 대상 縣 자료가 없어 포위를 풀었습니다.")
            return
        }
        val previousOwner = before.nationId
        try { garrisonOf(before) } catch (_: IllegalArgumentException) {
            lift(siege, "STATE_UNAVAILABLE"); return
        }
        val settlement = CountyCapture.settle(CountyCapture.CountyBefore(before.id, before.nationId,
            before.population, garrisonOf(before)), siege.besiegerNationId)
        // 점령군 수비대(2026-09-23 확정): 포위 군단이 부곡에서 수비병을 떼어 남긴다. 옛 수비대는 위 정산대로 인구가 된다.
        val left = corpsOf(siege.besiegerGeneralId)?.takeIf { it.orderId == siege.besiegerOrderId }
            ?.let { leaveGarrison(it, before.defenceMax) } ?: 0
        val after = withGarrison(before.copy(nationId = settlement.ownerNationId, population = settlement.population,
            supplyState = 0, frontState = 0), settlement.garrisonTroops + left, resetCondition = true)
        recorder.diffCity(PerTurnOverlay.toLogicCity(before), PerTurnOverlay.toLogicCity(after))
        world.applyCityDirtyFree(after)
        CapitalAfterCapture(world, recorder).settle(previousOwner, before.id)
        val now = now()
        world.putSiege(siege.copy(status = FALLEN, endReason = reason, garrison = 0,
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

    /**
     * 포위 군단 부곡에서 min(방비 상한 × %, 군단 병력 × %) 명을 떼어 낸다 — id 순 비례, 부곡마다 1명은 남긴다.
     * @return 실제로 뗀 수(縣 수비로 옮겨질 수).
     */
    private fun leaveGarrison(corps: DeployedCorps, defenceMax: Int): Int {
        val units = corps.bugokIds.sorted().mapNotNull { world.getBugokById(it) }.filter { it.troops > 1 }
        val total = units.sumOf { it.troops.toLong() }
        val want = minOf(defenceMax.coerceAtLeast(0).toLong() * CampaignBalance.CAPTURE_GARRISON_DEFENCE_MAX_PERCENT / 100,
            total * CampaignBalance.CAPTURE_GARRISON_MAX_CORPS_PERCENT / 100)
        if (want <= 0 || total <= 0) return 0
        var left = 0L
        for (unit in units) {
            val share = minOf(want * unit.troops / total, unit.troops - 1L)
            if (share <= 0) continue
            world.updateBugok(unit.copy(troops = Math.toIntExact(unit.troops - share)))
            left += share
        }
        return Math.toIntExact(left)
    }

    private fun lift(siege: Siege, reason: String) {
        world.putSiege(siege.copy(status = LIFTED, endReason = reason,
            timeline = appendEntry(siege.timeline, entry(now(), "LIFTED", siege.morale, siege.garrison, "reason" to reason))))
    }

    private fun endDeployment(corps: DeployedCorps) {
        updateMeta(corps.ownerGeneralId) { meta ->
            val deployment = DeploymentState.read(meta) ?: return@updateMeta meta
            val rest = deployment.corps.filterNot { it.orderId == corps.orderId }
            if (rest.isEmpty()) meta - DeploymentState.META_KEY
            else meta + (DeploymentState.META_KEY to DeploymentState(rest).toMetaValue())
        }
        updateMeta(corps.commanderGeneralId) { meta -> meta - CorpsOrder.META_KEY - CorpsMarchState.META_KEY }
    }

    private fun trimCorps(corps: DeployedCorps, remaining: List<Int>) {
        updateMeta(corps.ownerGeneralId) { meta ->
            val deployment = DeploymentState.read(meta) ?: return@updateMeta meta
            meta + (DeploymentState.META_KEY to DeploymentState(deployment.corps.map {
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

        private fun entry(at: Phase, event: String, morale: Int, garrison: Int, vararg extra: Pair<String, Any?>) =
            linkedMapOf<String, Any?>("year" to at.year, "month" to at.month, "phase" to at.phase, "event" to event,
                "morale" to morale, "garrison" to garrison).apply { extra.forEach { put(it.first, it.second) } }

        private fun appendEntry(timeline: List<Map<String, Any?>>, entry: Map<String, Any?>) =
            (timeline + entry).takeLast(CampaignBalance.SIEGE_TIMELINE_MAX)
    }
}
