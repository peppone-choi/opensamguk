package opensamguk.engine.config

import opensamguk.infra.seed.MapJson
import opensamguk.logic.world.SpatialSupplyNetwork
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
                loadNetwork = { _, _ -> error("must not load") },
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
            loadNetwork = { _, _ -> error("must not load") },
        )

        assertNull(provider())
    }

    @Test
    fun `Han provider validates immediately and refreshes live nations per snapshot`() {
        val mapData = MapJson.MapData(
            width = 10,
            height = 10,
            cities = listOf(
                MapJson.MapCityCoord(1, "A", 1.0, 1.0, provinceId = 0),
                MapJson.MapCityCoord(2, "B", 2.0, 2.0, provinceId = null),
            ),
        )
        var nation = 1
        val calls = mutableListOf<List<Int>>()
        val provider = createSpatialSupplyNetworkProvider(
            activeMapName = "han-world-v2",
            mapData = mapData,
            scenarioCode = 1020,
            liveCityNations = { listOf(1 to nation, 2 to 0) },
            loadNetwork = { scenario, cities ->
                assertEquals(1020, scenario)
                calls += cities.map { it.nationId }
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
            createSpatialSupplyNetworkProvider("han", duplicateMap, 1020, { listOf(1 to 1) }) { _, _ -> emptyNetwork }
        }

        val validMap = MapJson.MapData(
            1,
            1,
            listOf(MapJson.MapCityCoord(1, "A", 0.0, 0.0, provinceId = 0)),
        )
        assertFailsWith<IllegalStateException> {
            createSpatialSupplyNetworkProvider("han", validMap, 1020, { listOf(2 to 1) }) { _, _ -> emptyNetwork }
        }
    }
}
