package opensamguk.engine.world

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import opensamguk.gameapi.read.LiveCityOwnership
import opensamguk.gameapi.read.MapAdministrativeOwnership
import opensamguk.infra.seed.MapJson
import opensamguk.infra.seed.ScenarioJson
import opensamguk.logic.world.ActiveWorldMap
import opensamguk.logic.world.SupplyCapital
import opensamguk.logic.world.SupplyCity
import opensamguk.logic.world.computeSuppliedCitiesWithSpatialNetwork
import kotlin.io.path.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HanSpatialSupplyProviderTest {
    private val mapper = ObjectMapper()
    private val mapPath = "../../data/map/han-tiles.json"
    private val ownershipPath = "../../data/map/han-scenario-province-ownership-v1.json"
    private val allowlistPath = "../../data/map/han-scenario-jurisdiction-conflict-allowlist-v1.json"

    private fun provider() = HanSpatialSupplyProvider(mapper, mapPath, ownershipPath)

    @Test
    fun `canonical Han topology exposes 1524 provinces and 4161 symmetric edges`() {
        val network = provider().network(1020, emptyList())

        assertEquals(1_524, network.provinceOwners.size)
        assertEquals(1_524, network.provinceAdjacency.size)
        assertEquals(4_161, network.provinceAdjacency.sumOf(IntArray::size) / 2)
        network.provinceAdjacency.forEachIndexed { a, neighbors ->
            neighbors.forEach { b -> assertTrue(a in network.provinceAdjacency[b]) }
        }
    }

    @Test
    fun `all 15 scenario province owners match the map API projection`() {
        val api = MapAdministrativeOwnership(mapper, mapPath, ownershipPath, allowlistPath)
        val scenarioCodes = mapper.readTree(Path(ownershipPath).toFile()).path("scenarios")
            .map { it.path("scenarioCode").asInt() }

        assertEquals(15, scenarioCodes.size)
        for (scenarioCode in scenarioCodes) {
            val engineOwners = provider().network(scenarioCode, emptyList()).provinceOwners.toList()
            val apiOwners = api.project(scenarioCode.toString(), emptyList())
                .provinceOccupancy.map { it.nationId }
            assertEquals(apiOwners, engineOwners, "scenario $scenarioCode")
        }
    }

    @Test
    fun `live city seat override matches the map API projection`() {
        val city = SpatialSupplyCity(cityId = 720, provinceIndex = 846, nationId = 12)
        val engine = provider().network(1020, listOf(city))
        val api = MapAdministrativeOwnership(mapper, mapPath, ownershipPath, allowlistPath)
            .project(1020.toString(), listOf(LiveCityOwnership(720, 846, 12)))

        assertEquals(api.provinceOccupancy.map { it.nationId }, engine.provinceOwners.toList())
        assertEquals(846, engine.cityProvinceIndices.getValue(720))
    }

    @Test
    fun `all 15 scenarios have owned mapped seats and supplied mapped capitals`() {
        val cityCoords = MapJson.loadFromClasspath("han").cities
        val cityProvinceById = cityCoords.mapNotNull { city ->
            city.provinceId?.let { city.id to it }
        }.toMap()
        val cityConst = ActiveWorldMap.requireVariant(mapOf("mapName" to "han"), emptyMap())
        val scenarioCodes = mapper.readTree(Path(ownershipPath).toFile()).path("scenarios")
            .map { it.path("scenarioCode").asInt() }
        val expectedSupplyAudit = mapOf(
            1010 to Triple(224, 127, 97),
            1020 to Triple(390, 352, 38),
            1021 to Triple(385, 353, 32),
            1030 to Triple(471, 388, 83),
            1031 to Triple(500, 392, 108),
            1040 to Triple(485, 472, 13),
            1041 to Triple(520, 505, 15),
            1050 to Triple(606, 603, 3),
            1060 to Triple(606, 601, 5),
            1070 to Triple(628, 621, 7),
            1080 to Triple(651, 645, 6),
            1090 to Triple(686, 677, 9),
            1100 to Triple(706, 697, 9),
            1110 to Triple(706, 699, 7),
            1120 to Triple(271, 203, 68),
        )

        for (scenarioCode in scenarioCodes) {
            val scenario = ScenarioJson.loadScenario(
                Path("../../infra/src/main/resources/scenario/scenario_$scenarioCode.json").readText(),
            )
            val ownerByCity = scenario.nations.flatMap { nation ->
                nation.cities.map { cityToken -> cityToken.toInt() to nation.id }
            }.toMap()
            val liveCities = cityProvinceById.map { (cityId, provinceIndex) ->
                SpatialSupplyCity(cityId, provinceIndex, ownerByCity[cityId] ?: 0)
            }
            val network = provider().network(scenarioCode, liveCities)
            liveCities.forEach { city ->
                assertEquals(
                    city.nationId,
                    network.provinceOwners[city.provinceIndex],
                    "scenario $scenarioCode city ${city.cityId} seat occupancy",
                )
            }

            val ownedCities = liveCities.filter { it.nationId != 0 }
                .map { SupplyCity(it.cityId, it.nationId) }
            val capitals = scenario.nations.filter { it.scale > 0 }.mapNotNull { nation ->
                nation.cities.firstOrNull()?.toInt()?.let { SupplyCapital(it, nation.id) }
            }
            val supplied = computeSuppliedCitiesWithSpatialNetwork(
                cities = ownedCities,
                capitals = capitals,
                cityConst = cityConst,
                spatialNetwork = network,
            )
            assertEquals(
                expectedSupplyAudit.getValue(scenarioCode),
                Triple(ownedCities.size, supplied.size, ownedCities.size - supplied.size),
                "scenario $scenarioCode owned/supplied/blocked mapped city audit",
            )
            capitals.filter { it.capitalCityId in cityProvinceById }.forEach { capital ->
                assertTrue(
                    capital.capitalCityId in supplied,
                    "scenario $scenarioCode mapped capital ${capital.capitalCityId} is not supplied",
                )
            }
        }
    }

    @Test
    fun `Yuyang Lu seven provinces remain direct spatial supply inputs`() {
        val root = mapper.readTree(Path(mapPath).toFile())
        val lu = root.path("jurisdictionRecords").single { it.path("id").asText() == "87436" }
        val provinceIds = root.path("provinceRecords").map { it.path("id").asText() }
        val indices = lu.path("provinceIds").map { provinceIds.indexOf(it.asText()) }
        val network = provider().network(
            1020,
            listOf(SpatialSupplyCity(cityId = 720, provinceIndex = 846, nationId = 12)),
        )

        assertEquals(7, indices.size)
        assertEquals(setOf(12), indices.map { network.provinceOwners[it] }.toSet())
    }

    @Test
    fun `topology parser rejects self duplicate asymmetric and out-of-range edges`() {
        assertFailsWith<IllegalArgumentException> {
            validateSpatialAdjacency(listOf(intArrayOf(0)), provinceCount = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            validateSpatialAdjacency(listOf(intArrayOf(1, 1), intArrayOf(0)), provinceCount = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            validateSpatialAdjacency(listOf(intArrayOf(1), intArrayOf()), provinceCount = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            validateSpatialAdjacency(listOf(intArrayOf(2), intArrayOf()), provinceCount = 2)
        }
    }

    @Test
    fun `ownership parser rejects duplicate scenario and province assignments`() {
        val duplicateScenario = mapper.readTree(Path(ownershipPath).toFile())
        val scenarios = duplicateScenario.path("scenarios") as ArrayNode
        scenarios.add(scenarios.first())
        assertMalformedOwnershipRejected(duplicateScenario.toString())

        val duplicateAssignment = mapper.readTree(Path(ownershipPath).toFile())
        val assignments = duplicateAssignment.path("scenarios").first().path("assignments") as ArrayNode
        assignments.add(assignments.first())
        assertMalformedOwnershipRejected(duplicateAssignment.toString())
    }

    @Test
    fun `later network snapshots are isolated from adjacency mutation`() {
        val provider = provider()
        val first = provider.network(1020, emptyList())
        val originalNeighbor = first.provinceAdjacency.first { it.isNotEmpty() }.first()
        first.provinceAdjacency.first { it.isNotEmpty() }[0] = 0

        val second = provider.network(1020, emptyList())

        assertEquals(originalNeighbor, second.provinceAdjacency.first { it.isNotEmpty() }.first())
    }

    @Test
    fun `runtime city must map to its canonical jurisdiction seat province`() {
        val root = mapper.readTree(Path(mapPath).toFile())
        val lu = root.path("jurisdictionRecords").single { it.path("id").asText() == "87436" }
        val provinceIds = root.path("provinceRecords").map { it.path("id").asText() }
        val nonSeatProvince = lu.path("provinceIds")
            .map { provinceIds.indexOf(it.asText()) }
            .first { it != 846 }

        assertFailsWith<IllegalStateException> {
            provider().network(
                1020,
                listOf(SpatialSupplyCity(cityId = 720, provinceIndex = nonSeatProvince, nationId = 12)),
            )
        }
    }

    @Test
    fun `topology parser rejects malformed jurisdiction membership`() {
        val duplicateJurisdiction = mapper.readTree(Path(mapPath).toFile())
        val duplicateRecords = duplicateJurisdiction.path("jurisdictionRecords") as ArrayNode
        duplicateRecords.add(duplicateRecords.first())
        assertMalformedMapRejected(duplicateJurisdiction.toString())

        val unknownMember = mapper.readTree(Path(mapPath).toFile())
        val unknownProvinceIds = unknownMember.path("jurisdictionRecords").first().path("provinceIds") as ArrayNode
        unknownProvinceIds.set(0, mapper.nodeFactory.textNode("UNKNOWN-PROVINCE"))
        assertMalformedMapRejected(unknownMember.toString())

        val mismatchedMember = mapper.readTree(Path(mapPath).toFile())
        val jurisdictions = mismatchedMember.path("jurisdictionRecords")
        val foreignProvinceId = jurisdictions[1].path("provinceIds").first().asText()
        val firstProvinceIds = jurisdictions[0].path("provinceIds") as ArrayNode
        firstProvinceIds.set(0, mapper.nodeFactory.textNode(foreignProvinceId))
        assertMalformedMapRejected(mismatchedMember.toString())

        val missingMember = mapper.readTree(Path(mapPath).toFile())
        val incompleteProvinceIds = missingMember.path("jurisdictionRecords").first().path("provinceIds") as ArrayNode
        incompleteProvinceIds.remove(0)
        assertMalformedMapRejected(missingMember.toString())
    }

    private fun assertMalformedOwnershipRejected(json: String) {
        val malformed = createTempFile("han-spatial-supply-", ".json")
        try {
            malformed.toFile().writeText(json)
            assertFailsWith<IllegalStateException> {
                HanSpatialSupplyProvider(mapper, mapPath, malformed.toString()).network(1020, emptyList())
            }
        } finally {
            malformed.deleteIfExists()
        }
    }

    private fun assertMalformedMapRejected(json: String) {
        val malformed = createTempFile("han-spatial-map-", ".json")
        try {
            malformed.toFile().writeText(json)
            assertFailsWith<IllegalStateException> {
                HanSpatialSupplyProvider(mapper, malformed.toString(), ownershipPath).network(1020, emptyList())
            }
        } finally {
            malformed.deleteIfExists()
        }
    }
}
