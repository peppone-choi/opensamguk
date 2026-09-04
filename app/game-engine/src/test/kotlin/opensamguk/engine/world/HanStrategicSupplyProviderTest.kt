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

    @Test fun `V3 uses pinned dry land and keeps water out of political province ownership`() {
        val network = provider.network("han-world-v3", 1020, cities(),
            WaterControlSnapshot.fromTopology(projection.topology), projection)
        assertEquals(1524, network.provinceOwners.size)
        assertEquals(4117, network.provinceAdjacency.sumOf(IntArray::size) / 2)
        assertNotNull(network.strategicSupply)
        assertEquals(4161, provider.network("han-world-v2", 1020, emptyList()).provinceAdjacency.sumOf(IntArray::size) / 2)
        assertNull(provider.network("han-world-v2", 1020, emptyList()).strategicSupply)
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

    @Test fun `15 scenario dry graph introduces no new spatial supply cut from raw graph`() {
        val scenarios = mapper.readTree(Path.of("../../data/map/han-scenario-province-ownership-v1.json").toFile())
            .path("scenarios").map { it.path("scenarioCode").asInt() }
        assertEquals(15, scenarios.size)
        val cityConst = ActiveWorldMap.requireVariant(mapOf("mapName" to "han-world-v3"), emptyMap())
        for (code in scenarios) {
            val scenario = ScenarioJson.loadScenario(Path.of("../../infra/src/main/resources/scenario/scenario_$code.json").toFile().readText())
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
            assertEquals(emptySet(), before - after, "new cut in $code")
        }
    }
}
