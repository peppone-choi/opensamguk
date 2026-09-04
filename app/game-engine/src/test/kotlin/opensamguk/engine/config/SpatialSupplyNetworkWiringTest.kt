package opensamguk.engine.config

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.engine.world.HanSpatialSupplyProvider
import opensamguk.engine.world.HanSupplyDisconnectionPolicyLoader
import opensamguk.infra.seed.MapJson
import opensamguk.logic.world.SpatialSupplyNetwork
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SpatialSupplyNetworkWiringTest {
    private val emptyNetwork = SpatialSupplyNetwork(
        provinceOwners = intArrayOf(1),
        provinceAdjacency = listOf(intArrayOf()),
        cityProvinceIndices = mapOf(1 to 0),
    )

    @Test
    fun `Han mapping fails closed when the classpath map is empty`() {
        assertFailsWith<IllegalStateException> {
            createSpatialSupplyNetworkProvider(
                activeMapName = "han",
                mapData = MapJson.MapData(0, 0, emptyList()),
                scenarioCode = 1020,
                liveCityNations = { emptyList() },
                loadNetwork = { _, _, _ -> error("must not load") },
            )
        }
    }

    @Test
    fun `non-Han map retains legacy fallback without loading Han SSoT`() {
        val provider = createSpatialSupplyNetworkProvider(
            activeMapName = "legacy-fixture",
            mapData = MapJson.MapData(0, 0, emptyList()),
            scenarioCode = 1020,
            liveCityNations = { error("must not read cities") },
            loadNetwork = { _, _, _ -> error("must not load") },
        )

        assertNull(provider())
    }

    @Test
    fun `Han provider validates immediately and refreshes live nations per snapshot`() {
        val mapData = MapJson.MapData(
            width = 10,
            height = 10,
            cities = listOf(
                MapJson.MapCityCoord(
                    1, "A", 1.0, 1.0, provinceId = 0,
                    physicalPlaceRef = "chgis:v6:cnty:P1", routeNodeKey = "route-1",
                ),
                MapJson.MapCityCoord(2, "B", 2.0, 2.0, provinceId = null),
            ),
        )
        var nation = 1
        val calls = mutableListOf<List<Int>>()
        val provider = createSpatialSupplyNetworkProvider(
            activeMapName = "han-world-v3",
            mapData = mapData,
            scenarioCode = 1020,
            liveCityNations = { listOf(1 to nation, 2 to 0) },
            loadNetwork = { mapName, scenario, cities ->
                assertEquals("han-world-v3", mapName)
                assertEquals(1020, scenario)
                calls += cities.map { it.nationId }
                assertEquals("chgis:v6:cnty:P1", cities.single().physicalPlaceRef)
                assertEquals("route-1", cities.single().routeNodeKey)
                emptyNetwork
            },
        )

        assertEquals(listOf(listOf(1)), calls)
        nation = 3
        assertEquals(emptyNetwork, provider())
        assertEquals(listOf(listOf(1), listOf(3)), calls)
    }

    @Test
    fun `Han mapping rejects duplicate map city ids and runtime drift`() {
        val duplicateMap = MapJson.MapData(
            1,
            1,
            listOf(
                MapJson.MapCityCoord(1, "A", 0.0, 0.0, provinceId = 0),
                MapJson.MapCityCoord(1, "B", 0.0, 0.0, provinceId = 1),
            ),
        )
        assertFailsWith<IllegalStateException> {
            createSpatialSupplyNetworkProvider("han", duplicateMap, 1020, { listOf(1 to 1) }) { _, _, _ -> emptyNetwork }
        }

        val validMap = MapJson.MapData(
            1,
            1,
            listOf(MapJson.MapCityCoord(1, "A", 0.0, 0.0, provinceId = 0)),
        )
        assertFailsWith<IllegalStateException> {
            createSpatialSupplyNetworkProvider("han", validMap, 1020, { listOf(2 to 1) }) { _, _, _ -> emptyNetwork }
        }
    }

    @Test
    fun `daemon eager snapshot loads the V3 policy domain for all active scenarios`() {
        val mapper = ObjectMapper()
        val loader = HanSupplyDisconnectionPolicyLoader(
            objectMapper = mapper,
            ledgerPath = "../../data/curated/han/supply-disconnection-adjudications-v1.json",
            mapPath = "../../data/map/han-tiles.json",
            runtimeMapPath = "../../infra/src/main/resources/map/han.json",
            sourceLedgerPath = "../../data/curated/han/territory-disconnection-adjudications-v1.json",
            v3LedgerPath = "../../data/curated/han/supply-disconnection-adjudications-v3.json",
            v3RuntimeMapPath = "../../infra/src/main/resources/map/han-world-v3.json",
        )
        val spatial = HanSpatialSupplyProvider(
            mapper,
            "../../data/map/han-tiles.json",
            "../../data/map/han-scenario-province-ownership-v1.json",
            loader,
        )
        val mapData = MapJson.loadFromClasspath("han-world-v3")
        val scenarioCodes = mapper.readTree(
            Path.of("../../data/map/han-scenario-province-ownership-v1.json").toFile(),
        ).path("scenarios").map { it.path("scenarioCode").asInt() }

        assertEquals(15, scenarioCodes.size)
        for (scenarioCode in scenarioCodes) {
            val snapshot = createSpatialSupplyNetworkProvider(
                activeMapName = "han-world-v3",
                mapData = mapData,
                scenarioCode = scenarioCode,
                liveCityNations = { mapData.cities.map { it.id to 0 } },
                loadNetwork = spatial::network,
            )()
            val expectedPolicies = buildSet {
                if (scenarioCode in 1020..1110) add(305)
                if (scenarioCode in 1030..1110) add(548)
            }
            assertEquals(expectedPolicies, snapshot?.fallbackPolicies?.keys, "scenario $scenarioCode")
            assertEquals(null, snapshot?.fallbackPolicies?.get(364), "Zhu-a scenario $scenarioCode")
        }
    }
}
