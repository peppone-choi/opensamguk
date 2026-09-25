package opensamguk.engine.world

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.seed.HanStrategicTopologyJson
import opensamguk.infra.seed.MapJson
import opensamguk.infra.seed.ScenarioJson
import opensamguk.logic.world.*
import java.nio.file.Path
import kotlin.test.*

class HanStrategicSupplyProviderTest {
    private val mapper = ObjectMapper()
    private val projection by lazy { HanStrategicTopologyJson.loadFromDirectory(Path.of("../.."), "han-world-v3") }
    private val provider by lazy {
        HanSpatialSupplyProvider(mapper, "../../data/map/han-tiles.json",
            "../../data/map/han-scenario-province-ownership-v1.json")
    }
    private fun cities(owners: Map<Int, Int> = emptyMap()) =
        MapJson.loadFromClasspath("han-world-v3").cities.mapNotNull { city ->
            city.provinceId?.let { SpatialSupplyCity(city.id, it, owners[city.id] ?: 0,
                city.physicalPlaceRef, city.routeNodeKey) }
        }

    @Test fun `historical supply uses frozen ownership links and policies without runtime files`() {
        val artifacts = opensamguk.infra.seed.HanWorldArtifactsResolver(Path.of("../.."))
        val policy = HanSupplyDisconnectionPolicyLoader(mapper, "/missing/legacy", "/missing/tiles", "/missing/map", "/missing/source",
            "/missing/v3policy", "/missing/v3map")
        val historicalProvider = HanSpatialSupplyProvider(mapper, "/missing/tiles", "/missing/owners", policy,
            CommanderySupplyLinkLoader(mapper, "/missing/links"))
        for (variant in HanWorldVariant.entries) {
            val bundle = artifacts.artifacts(variant)
            val map = MapJson.loadMap(bundle.artifactBytes("infra/src/main/resources/map/han-world-v3.json").toString(Charsets.UTF_8))
            val live = map.cities.mapNotNull { city -> city.provinceId?.let {
                SpatialSupplyCity(city.id, it, 0, city.physicalPlaceRef, city.routeNodeKey)
            } }
            val network = historicalProvider.network("han-world-v3", 1020, live,
                WaterControlSnapshot.fromTopology(bundle.projection.topology), artifacts = bundle)
            assertEquals(bundle.projection.topology.contentHash, network.strategicSupply?.topology?.contentHash)
            assertEquals(live.associate { it.cityId to it.provinceIndex }, network.cityProvinceIndices)
            assertFailsWith<IllegalArgumentException> {
                historicalProvider.network("han-world-v3", 1020, live.mapIndexed { i, city ->
                    if (i == 0) city.copy(physicalPlaceRef = "wrong") else city
                }, artifacts = bundle)
            }
            val other = HanWorldVariant.entries.first { it != variant }
            assertFailsWith<IllegalArgumentException> {
                historicalProvider.network("han-world-v3", 1020, live, strategicProjection = artifacts.artifacts(other).projection, artifacts = bundle)
            }
        }
    }

    @Test fun `V3 uses pinned dry land and keeps water out of political province ownership`() {
        val network = provider.network("han-world-v3", 1020, cities(),
            WaterControlSnapshot.fromTopology(projection.topology), projection)
        assertEquals(1627, network.provinceOwners.size)  // 4배 지도 구역 재편 후
        val provinceIndex = network.strategicSupply!!.provinceIds.withIndex().associate { it.value to it.index }
        projection.topology.traversalEdges.filter { it.mode == TraversalMode.LAND }.forEach { edge ->
            val a = provinceIndex.getValue((edge.from as StrategicNodeRef.LandProvince).id)
            val b = provinceIndex.getValue((edge.to as StrategicNodeRef.LandProvince).id)
            assertTrue(b in network.provinceAdjacency[a], "missing land adjacency ${edge.id}")
            assertTrue(a in network.provinceAdjacency[b], "asymmetric land adjacency ${edge.id}")
        }
        assertNotNull(network.strategicSupply)
        val legacy = provider.network("han-world-v2", 1020, emptyList())
        assertTrue(legacy.provinceAdjacency.any { it.isNotEmpty() })
        assertNull(legacy.strategicSupply)
    }

    @Test fun `V3 rejects mismatched route physical and province binding before supply`() {
        val live = cities()
        assertFailsWith<IllegalArgumentException> {
            provider.network("han-world-v3", 1020, listOf(live.first().copy(physicalPlaceRef = "wrong")), null, projection)
        }
        assertFailsWith<IllegalArgumentException> {
            provider.network("han-world-v3", 1020, listOf(live.first().copy(routeNodeKey = "wrong")), null, projection)
        }
    }

    @Test fun `reviewed scenarios and the campaign start with no new supply cuts from built roads`() {
        val scenarios = mapper.readTree(Path.of("../../data/map/han-scenario-province-ownership-v1.json").toFile())
            .path("scenarios").map { it.path("scenarioCode").asInt() }
        assertEquals(15, scenarios.size)
        val cityConst = ActiveWorldMap.requireVariant(mapOf("mapName" to "han-world-v3"), emptyMap())
        val newCuts = mutableMapOf<Int, Set<Int>>()
        for (code in scenarios + 990002) {
            val path = if (code == 990002) "../../tools/e2e/fixtures/hwiha-yuzhou/scenario_990002.json"
                else "../../infra/src/main/resources/scenario/scenario_$code.json"
            val scenario = ScenarioJson.loadScenario(Path.of(path).toFile().readText())
            val owners = scenario.nations.flatMap { n -> n.cities.map { it.toInt() to n.id } }.toMap()
            val live = cities(owners)
            val owned = live.filter { it.nationId > 0 }.map { SupplyCity(it.cityId, it.nationId) }
            val capitals = scenario.nations.filter { it.scale > 0 }.mapNotNull { n ->
                n.cities.firstOrNull()?.toInt()?.let { SupplyCapital(it, n.id) }
            }
            val raw = provider.network("han-world-v2", code, live)
            val dry = provider.network("han-world-v3", code, live, null, projection)
            assertContentEquals(raw.provinceOwners, dry.provinceOwners, "political ownership drift $code")
            val before = evaluateSupplyReachability(owned, capitals, cityConst, raw).rows
                .filter { it.spatialGraphSupplied }.map { it.cityId }.toSet()
            val after = evaluateSupplyReachability(owned, capitals, cityConst, dry).rows
                .filter { it.spatialGraphSupplied }.map { it.cityId }.toSet()
            assertEquals(emptySet(), after - before, "road graph created supply reachability in $code")
            if (before - after != emptySet<Int>()) newCuts[code] = before - after
        }
        assertEquals(emptyMap(), newCuts, "initial roads cut supply")
    }

    @Test fun `closing an initial road makes the no new supply cuts gate red`() {
        val code = 1100
        val scenario = ScenarioJson.loadScenario(Path.of("../../infra/src/main/resources/scenario/scenario_$code.json").toFile().readText())
        val owners = scenario.nations.flatMap { n -> n.cities.map { it.toInt() to n.id } }.toMap()
        val live = cities(owners)
        val owned = live.filter { it.nationId > 0 }.map { SupplyCity(it.cityId, it.nationId) }
        val capitals = scenario.nations.filter { it.scale > 0 }.mapNotNull { n ->
            n.cities.firstOrNull()?.toInt()?.let { SupplyCapital(it, n.id) }
        }
        val raw = provider.network("han-world-v2", code, live)
        val dry = provider.network("han-world-v3", code, live, null, projection)
        val strategic = requireNotNull(dry.strategicSupply)
        val bridgeId = "land-boundary:6:20006412:gc-g0068-003"
        assertTrue(strategic.topology.traversalEdges.single { it.id == bridgeId }.initiallyOpen)
        val closed = dry.copy(strategicSupply = strategic.withEdgeStates(mapOf(
            1 to StrategicEdgeStateSnapshot(strategic.topology.topologyRevision,
                strategic.topology.contentHash, mapOf(bridgeId to StrategicEdgeState(active = false)))
        )))
        fun supplied(network: SpatialSupplyNetwork) = evaluateSupplyReachability(
            owned, capitals, ActiveWorldMap.requireVariant(mapOf("mapName" to "han-world-v3"), emptyMap()), network
        ).rows.filter { it.spatialGraphSupplied }.map { it.cityId }.toSet()
        assertEquals(emptySet(), supplied(raw) - supplied(dry))
        assertTrue((supplied(raw) - supplied(closed)).isNotEmpty(), "closed road must trip the initial supply invariant")
    }
}
