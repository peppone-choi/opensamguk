package opensamguk.engine.hwiha

import java.nio.file.Path
import java.time.Instant
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/**
 * In-memory HWIHA world on the real archived map (no database). Synthetic people and units only; the map,
 * topology, march metrics and province cells are the pinned artifacts the engine uses in production.
 */
internal class HwihaCampaignWorldFixture(val variant: HanWorldVariant = HanWorldVariant.V3_1168) {
    val bundle = cache.getOrPut(variant) { HanWorldArtifactsResolver(Path.of("../..")).artifacts(variant) }
    val topology get() = bundle.projection.topology
    val metrics get() = bundle.landMarchMetrics
    val cells get() = bundle.provinceCells
    private val provinceOfCity = bundle.projection.bindingsByCityId
        .mapNotNull { (city, binding) -> binding.landProvinceId?.let { city to it } }.toMap()

    /** A march P0 → … → destination whose first edge fits one turn and whose second province supports a battle. */
    data class Route(val start: StrategicNodeRef.LandProvince, val first: StrategicNodeRef.LandProvince,
        val destination: StrategicNodeRef.LandProvince, val startCity: Int, val destinationCounty: Int)

    fun passage() = HwihaLandPassageState.read(
        mapOf(HwihaLandPassageState.META_KEY to HwihaLandPassageState.initialMetaValue(topology)), topology)!!

    fun route(): Route = routeCache.getOrPut(topology.contentHash) {
        val edges = passage()
        val counties = bundle.projection.administrativeCountyIds.sorted()
        val countyProvinces = counties.mapNotNull { provinceOfCity[it] }.toSet()
        val neighbors = HashMap<String, MutableSet<String>>()
        for (edge in topology.traversalEdges.filter(LandMarchMetricSnapshot::supports)) {
            val from = (edge.from as? StrategicNodeRef.LandProvince)?.id ?: continue
            val to = (edge.to as? StrategicNodeRef.LandProvince)?.id ?: continue
            neighbors.getOrPut(from) { sortedSetOf() }.add(to)
            if (!edge.directed) neighbors.getOrPut(to) { sortedSetOf() }.add(from)
        }
        for (startCity in counties) {
            val start = StrategicNodeRef.LandProvince(provinceOfCity[startCity] ?: continue)
            val candidates = neighbors[start.id].orEmpty().flatMap { neighbors[it].orEmpty() }
                .filter { it != start.id && it in countyProvinces }.distinct().sorted()
            for (target in candidates) {
                val destination = StrategicNodeRef.LandProvince(target)
                val county = counties.first { provinceOfCity[it] == target }
                val path = (StrategicPathResolver.resolveLandMarch(topology, StrategicPathRequest(start, destination, 1),
                    edges, metrics) as? LandMarchPathResult.Resolved)?.path ?: continue
                if (path.edgeIds.size != 2) continue
                if (path.edgeIds.any { metrics.edgesById.getValue(it).costMm > LandMarchMetricSnapshot.NORMAL_BUDGET_MM }) continue
                val first = StrategicNodeRef.LandProvince(path.nodeKeys[1].removePrefix("land:"))
                if (first.id in countyProvinces) continue
                val layout = HwihaBattlefieldLayout.prepare(cells, first.id, start.id) as? HwihaBattlefieldLayout.Result.Ready
                    ?: continue
                if (layout.layout.defenderZone.isEmpty() || layout.layout.attackerZone.isEmpty()) continue
                val siege = HwihaBattlefieldLayout.prepare(cells, destination.id, first.id) as? HwihaBattlefieldLayout.Result.Ready
                    ?: continue
                if (siege.layout.defenderZone.size < 2) continue
                return@getOrPut Route(start, first, destination, startCity, county)
            }
        }
        error("no two-edge battle route on the archived map")
    }

    fun cityIn(province: StrategicNodeRef.LandProvince): Int =
        provinceOfCity.entries.filter { it.value == province.id }.minOf { it.key }

    fun person(id: Int, nationId: Int, cityId: Int, userId: String? = null, lord: Boolean = true,
        stats: GeneralStats = GeneralStats(70, 70, 70, 70, 70)) =
        TurnGeneral(id = id, userId = userId, name = "G$id", nationId = nationId, cityId = cityId, troopId = 0,
            stats = stats, experience = 0, dedication = 0, officerLevel = if (lord) 12 else 1, npcState = 2,
            turnTime = Instant.parse("0200-01-01T00:00:00Z"), meta = mapOf(HwihaLordStatus.META_KEY to lord,
                HwihaPersonPolicyState.META_KEY to HwihaPersonPolicyState(30, true, "synthetic-test", "1", id).toMetaValue()))

    fun world(
        generals: List<Pair<TurnGeneral, StrategicNodeRef.LandProvince>>,
        bugoks: List<Bugok> = emptyList(),
        nations: List<Nation> = listOf(Nation(1, "N1", "#111111"), Nation(2, "N2", "#222222")),
        wars: List<Pair<Int, Int>> = listOf(1 to 2),
        cityChanges: (City) -> City = { it },
        retainers: List<Retainer> = emptyList(),
        extraStateMeta: Map<String, Any?> = emptyMap(),
    ): InMemoryTurnWorld {
        val positions = generals.fold(GeneralPositionSnapshot(topology.topologyRevision, topology.contentHash,
            topology.landProvinceIds, topology.waterZones.map { it.id }.toSet())) { snapshot, (general, node) ->
            snapshot.withState(GeneralPositionState(topology.topologyRevision, topology.contentHash, general.id, node, 1))
        }
        val cities = bundle.cityConst.all().keys.sorted().map { id ->
            cityChanges(City(id, "C$id", 0, 1, population = 1000, populationMax = 10000, agriculture = 100,
                agricultureMax = 1000, commerce = 100, commerceMax = 1000, defence = 100, defenceMax = 1000,
                wall = 100, wallMax = 1000, meta = mapOf("trust" to 50.0)))
        }
        val state = TurnWorldState(1, 200, 1, 3600, Instant.parse("0200-01-01T00:00:00Z"), currentPhase = 1,
            config = mapOf("ruleProfile" to "HWIHA", "mapName" to "han-world-v3"), hanWorldVariant = variant,
            meta = mapOf(HwihaLandPassageState.META_KEY to HwihaLandPassageState.initialMetaValue(topology),
                HwihaMarchReactions.META_KEY to HwihaMarchReactions.Empty.toMetaValue(),
                "startYear" to 200, "startTime" to "0200-01-01T00:00:00Z") + extraStateMeta)
        return InMemoryTurnWorld(WorldSnapshot(worldId = WorldId(1), state = state, generals = generals.map { it.first },
            cities = cities, nations = nations, bugoks = bugoks, retainers = retainers,
            diplomacy = wars.flatMap { (a, b) -> listOf(TurnDiplomacy(a, b, 0, 0), TurnDiplomacy(b, a, 0, 0)) },
            generalPositionSnapshot = positions, cityLandProvinceById = provinceOfCity,
            administrativeCountyIds = bundle.projection.administrativeCountyIds))
    }

    fun unit(id: Int, owner: Int, troops: Int, crewTypeId: Int = 1100, provisions: Int = 100_000) =
        Bugok(id, owner, "U$id", troops, crewTypeId, training = 50, morale = 50, provisions = provisions)

    /** Deploys [ownerId]'s own units under its own command with a durable order to [destination]. */
    fun deploy(world: InMemoryTurnWorld, recorder: ChangeRecorder, ownerId: Int, bugokIds: List<Int>,
        destination: StrategicNodeRef.LandProvince, orderId: String = "order-$ownerId") {
        val applied = HwihaDeploymentExecutor(world, recorder, topology, metrics)
            .deploy(orderId, DeploymentRequest(ownerId, null, bugokIds))
        check(applied is DeploymentExecution.Applied) { "deployment rejected: $applied" }
        val before = world.getGeneralById(ownerId)!!
        val order = HwihaCorpsOrder(orderId, ownerId, ownerId, destination, topology.topologyRevision, topology.contentHash)
        val after = before.copy(meta = before.meta + (HwihaCorpsOrder.META_KEY to order.toMetaValue()))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
    }

    fun nextPhase(world: InMemoryTurnWorld) {
        val next = world.getState().let { HwihaPhase(it.currentYear, it.currentMonth, it.currentPhase) }.plus(1)
        world.setCurrentDate(next.year, next.month, next.phase)
    }

    fun movement(world: InMemoryTurnWorld, recorder: ChangeRecorder, outcomes: HwihaWarOutcomeListener = HwihaWarOutcomeListener.NONE) =
        HwihaAssignmentMarchTurn(world, recorder, topology, metrics, cells, outcomes)

    /** Captures the war-outcome boundary calls; the renown writer itself belongs to the records stream. */
    class RecordingOutcomes : HwihaWarOutcomeListener {
        val encounters = mutableListOf<Pair<List<Int>, List<Int>>>()
        val captures = mutableListOf<List<Any>>()
        override fun onEncounterResolved(winnerIds: List<Int>, loserIds: List<Int>) { encounters += winnerIds to loserIds }
        override fun onCountyCaptured(countyId: Int, previousNationId: Int, captorNationId: Int, capturerIds: List<Int>) {
            captures += listOf(countyId, previousNationId, captorNationId, capturerIds)
        }
    }

    companion object {
        val NO_INPUT = ReservedTurn("휴식", "{}", rowExists = false)
        private val cache = HashMap<HanWorldVariant, opensamguk.infra.seed.ResolvedHanWorldArtifacts>()
        private val routeCache = HashMap<String, Route>()
    }
}
