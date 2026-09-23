package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.input.*
import opensamguk.logic.war.hwiha.HwihaS3Provisional
import opensamguk.logic.war.hwiha.HwihaSiegeRules
import opensamguk.logic.world.*

/**
 * NPC 출병 선택기(#792 일부) — 사람 소유가 아닌 장수가 예약 없이 턴을 맞으면 `action.deploy` 를 골라 준다.
 * 입력만 고르고 실행·기록은 기존 출병 핸들러가 한다(출사 선택기와 같은 방식).
 *
 * 고르는 규칙(임시, `hwiha-s3-provisional-v1.json` npcDeploy): 세력에 속했고, 출전·조우·포위 중이 아니며,
 * 직속(부장 지휘 아님) 부곡이 있는 NPC 가 현재 省에서 간선 [HwihaS3Provisional.NPC_DEPLOY_MAX_EDGES] 개 이내의
 * 적대(교전 중 또는 무주) 縣治 가운데 병력이 수비병 × [HwihaS3Provisional.NPC_DEPLOY_MIN_RATIO] 이상인 곳을
 * 교전 세력 縣을 무주 縣보다 먼저 보고, 각 묶음에서는 가까운 간선 고리(省 hop)부터,
 * 같은 고리 안에서는 경로 비용 → 縣 id 순으로 하나 고른다. 다른 아군 군단이 이미 향하거나 포위 중인 縣은 뺀다.
 *
 * **구원이 먼저다**: 자국 縣이 적에게 포위돼 있고 그 포위 군단 병력 이하로 갈 수 있으면 그 縣으로 출병한다
 * (포위 중인 NPC 도 포위를 풀고 구원한다). 부곡이 굶었으면 공격 출병 대신 가장 가까운 자국 縣으로 돌아간다
 * (포위 군단이 있는 省에 들어가면 조우가 일어난다). 같은 입력이면 같은 선택이다 — 난수·벽시계·맵 순회 순서를 쓰지 않는다.
 */
class HwihaNpcDeploySelector(
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
) {
    fun select(world: InMemoryTurnWorld, actorId: Int, reserved: ReservedTurn): ReservedTurn {
        if (world.ruleProfile != RuleProfile.HWIHA || reserved.rowExists || !HwihaPersonalTurn.hasNoInput(reserved)) return reserved
        val choice = choose(world, actorId) ?: return reserved
        return ReservedTurn(HwihaDeployInput.INPUT_ID, HwihaDeployInput.canonicalJson(choice), brief = "출병", rowExists = false)
    }

    fun choose(world: InMemoryTurnWorld, actorId: Int): DeployInput? {
        val actor = world.getGeneralById(actorId) ?: return null
        if (!isUnowned(actor.userId) || actor.nationId <= 0) return null
        // A held NPC card moves through its holder's deployment and standing policy.
        if (world.listRetainers().any { it.generalId == actorId }) return null
        if (HwihaCorpsEncounter.META_KEY in actor.meta || HwihaCountyAssignment.META_KEY in actor.meta) return null
        if (world.listHwihaSieges().any { it.status == HwihaSiegeService.ACTIVE && it.besiegerGeneralId == actorId }) return null
        val projection = HwihaDeploymentExecutor(world, ChangeRecorder(), topology, metrics).projection() ?: return null
        if (projection.deployed.any { it.commanderGeneralId == actorId || it.ownerGeneralId == actorId }) return null
        val units = world.bugoksOf(actorId).filter { it.commanderRetainerId == null && it.troops > 0 }
        if (units.isEmpty()) return null
        val troops = units.sumOf { it.troops.toLong() }
        val position = world.positionOf(actorId) as? StrategicNodeRef.LandProvince ?: return null
        val edges = try { HwihaLandPassageState.read(world.getState().meta, topology) } catch (_: IllegalArgumentException) { null }
            ?: return null
        reliefTarget(world, actor.nationId, position, troops, projection, edges)?.let { return DeployInput(actorId, units.map { it.id }.sorted(), it) }
        // 급식이 안 되는 부곡으로는 공격 출병하지 않는다 — 도착해도 포위를 걸 수 없다. 자국 縣에 있으면 월 보충을 기다리고,
        // 적지·무주지에 있으면 가장 가까운 자국 縣으로 돌아간다(적지에서는 보충받지 못한다).
        if (!units.all { HwihaSiegeRules.besiegerFed(it.troops, it.provisions) }) {
            val here = world.administrativeCountyIds.any { world.landNodeOfCity(it) == position && world.getCityById(it)?.nationId == actor.nationId }
            if (here) return null
            val home = world.administrativeCountyIds.sorted().mapNotNull { countyId ->
                val node = world.landNodeOfCity(countyId) as? StrategicNodeRef.LandProvince ?: return@mapNotNull null
                if (world.getCityById(countyId)?.nationId != actor.nationId) return@mapNotNull null
                route(position, node, edges)?.let { Triple(it.totalCostMm, countyId, node) }
            }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: return null
            return DeployInput(actorId, units.map { it.id }.sorted(), home.third)
        }
        val wars = world.listDiplomacy().filter { it.state == 0 }.mapTo(hashSetOf()) { it.fromNationId to it.toNationId }
        val claimed = world.listHwihaSieges().filter { it.status == HwihaSiegeService.ACTIVE }.map { it.countyId }.toSet() +
            projection.people.mapNotNull { person ->
                world.getGeneralById(person.id)?.takeIf { it.nationId == actor.nationId }
                    ?.let { try { HwihaCorpsOrder.read(it.meta, topology) } catch (_: IllegalArgumentException) { null } }
                    ?.destination?.let { node -> world.administrativeCountyIds.filter { world.landNodeOfCity(it) == node } }
            }.flatten()
        val hops = provinceHops(position, HwihaS3Provisional.NPC_DEPLOY_MAX_EDGES)
        val near = hops.keys
        fun route(node: StrategicNodeRef.LandProvince) = route(position, node, edges)
        val targets = world.administrativeCountyIds.sorted().mapNotNull { countyId ->
            val node = world.landNodeOfCity(countyId) as? StrategicNodeRef.LandProvince ?: return@mapNotNull null
            if (node == position || node.id !in near || countyId in claimed) return@mapNotNull null
            val city = world.getCityById(countyId) ?: return@mapNotNull null
            val hostile = city.nationId != actor.nationId && (city.nationId == 0 ||
                (actor.nationId to city.nationId) in wars || (city.nationId to actor.nationId) in wars)
            if (!hostile || troops < city.defence.coerceAtLeast(0).toLong() * HwihaS3Provisional.NPC_DEPLOY_MIN_RATIO) return@mapNotNull null
            (city.nationId != 0) to Triple(hops.getValue(node.id), countyId, node)
        }
        // The neutral buffer in the full 1224-county scenario otherwise consumes every NPC
        // turn before the warring armies meet. Keep distance and path-cost ordering inside
        // each owner class, and fall back to neutral expansion if no war target is reachable.
        for (enemyOwned in listOf(true, false)) {
            val candidates = targets.filter { it.first == enemyOwned }.map { it.second }
            for (ring in candidates.map { it.first }.distinct().sorted()) {
                val best = candidates.filter { it.first == ring }.mapNotNull { (_, countyId, node) ->
                    route(node)?.let { Triple(it.totalCostMm, countyId, node) }
                }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: continue
                return DeployInput(actorId, units.map { it.id }.sorted(), best.third)
            }
        }
        return null
    }

    /**
     * 구원이 필요한 자국 縣 — NPC 포위 지휘관도 자기 턴에 이것을 보고 포위를 푼다(2026-09-23 사용자 결정: 포위 중에도 구원).
     * 출전 중이 아니거나 포위 중인 NPC 가 [actorId] 의 직속 부곡으로 갈 수 있는 곳만. 없으면 null.
     */
    fun reliefFor(world: InMemoryTurnWorld, actorId: Int): StrategicNodeRef.LandProvince? {
        val actor = world.getGeneralById(actorId) ?: return null
        if (!isUnowned(actor.userId) || actor.nationId <= 0) return null
        if (world.listRetainers().any { it.generalId == actorId }) return null
        val troops = world.bugoksOf(actorId).filter { it.commanderRetainerId == null && it.troops > 0 }.sumOf { it.troops.toLong() }
        if (troops <= 0) return null
        val position = world.positionOf(actorId) as? StrategicNodeRef.LandProvince ?: return null
        val projection = HwihaDeploymentExecutor(world, ChangeRecorder(), topology, metrics).projection() ?: return null
        val edges = try { HwihaLandPassageState.read(world.getState().meta, topology) } catch (_: IllegalArgumentException) { null }
            ?: return null
        return reliefTarget(world, actor.nationId, position, troops, projection, edges)
    }

    /** Relief first: an own county under enemy siege that this army can at least match, nearest by march cost then county id. */
    private fun reliefTarget(world: InMemoryTurnWorld, nationId: Int, position: StrategicNodeRef.LandProvince, troops: Long,
        projection: DeploymentProjection, edges: StrategicEdgeStateSnapshot): StrategicNodeRef.LandProvince? {
        val near = provinceHops(position, HwihaS3Provisional.NPC_DEPLOY_MAX_EDGES).keys
        return world.listHwihaSieges().filter { it.status == HwihaSiegeService.ACTIVE }.sortedBy { it.countyId }.mapNotNull { siege ->
            val city = world.getCityById(siege.countyId)?.takeIf { it.nationId == nationId } ?: return@mapNotNull null
            val node = world.landNodeOfCity(city.id) as? StrategicNodeRef.LandProvince ?: return@mapNotNull null
            if (node == position || node.id !in near) return@mapNotNull null
            val besieger = projection.deployed.singleOrNull { it.orderId == siege.besiegerOrderId } ?: return@mapNotNull null
            val besiegers = besieger.bugokIds.sumOf { (world.getBugokById(it)?.troops ?: 0).toLong() }
            if (troops * HwihaS3Provisional.NPC_RELIEF_MIN_RATIO_PERCENT < besiegers * 100) return@mapNotNull null
            val path = route(position, node, edges) ?: return@mapNotNull null
            Triple(path.totalCostMm, city.id, node)
        }.minWithOrNull(compareBy({ it.first }, { it.second }))?.third
    }

    private fun route(from: StrategicNodeRef.LandProvince, to: StrategicNodeRef.LandProvince, edges: StrategicEdgeStateSnapshot) =
        (StrategicPathResolver.resolveLandMarch(topology, StrategicPathRequest(from, to, 1), edges, metrics)
            as? LandMarchPathResult.Resolved)?.path?.takeIf { it.edgeIds.size <= HwihaS3Provisional.NPC_DEPLOY_MAX_EDGES }

    /** Topology hop distance (not march cost) within [hops]: a cheap ring order before exact path resolution. */
    private fun provinceHops(start: StrategicNodeRef.LandProvince, hops: Int): Map<String, Int> {
        val neighbors = HashMap<String, MutableList<String>>()
        for (edge in topology.traversalEdges.filter(LandMarchMetricSnapshot::supports)) {
            val from = (edge.from as? StrategicNodeRef.LandProvince)?.id ?: continue
            val to = (edge.to as? StrategicNodeRef.LandProvince)?.id ?: continue
            neighbors.getOrPut(from) { mutableListOf() }.add(to)
            if (!edge.directed) neighbors.getOrPut(to) { mutableListOf() }.add(from)
        }
        val seen = linkedMapOf(start.id to 0)
        var frontier = listOf(start.id)
        for (hop in 1..hops) {
            frontier = frontier.flatMap { neighbors[it].orEmpty() }.filter { seen.putIfAbsent(it, hop) == null }
        }
        return seen
    }

    companion object {
        fun isUnowned(userId: String?) = userId.isNullOrBlank() || userId.toLongOrNull()?.let { it <= 0 } == true

        /** NPC 출병 명령 식별자 — 요청 행이 없으므로 (월드, 장수, 순)에서 결정론으로 만든다. */
        fun orderId(world: InMemoryTurnWorld, actorId: Int): String = world.getState().let {
            "npc-deploy:${world.worldId.value}:$actorId:${it.currentYear}:${it.currentMonth}:${it.currentPhase}"
        }
    }
}
