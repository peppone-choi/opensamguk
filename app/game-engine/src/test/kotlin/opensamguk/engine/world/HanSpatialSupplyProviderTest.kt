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
import opensamguk.logic.world.SupplyReachabilityVerdict
import opensamguk.logic.world.evaluateSupplyReachability
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
    private val ledgerPath = "../../data/curated/han/supply-disconnection-adjudications-v1.json"
    private val sourceLedgerPath = "../../data/curated/han/territory-disconnection-adjudications-v1.json"
    private val runtimeMapPath = "../../infra/src/main/resources/map/han.json"

    private fun provider() = HanSpatialSupplyProvider(mapper, mapPath, ownershipPath)

    /**
     * 2026-09-17(ADR-LITE-056): legacy v2 han.json 의 두 城은 현행 han-tiles 에서 제 관할의 治所 省이 아니다 —
     * 576 漢昌(蒼溪)은 巴中 관할(44621)에, 622 富平(涇陽 寄治)은 靈武 富平 관할(70524)에 접혔다(같은 縣의 중복 자리).
     * v3 세계에는 그 자리 城이 없다. v2 城 목록을 현행 타일에 얹는 이 검사들만 그 둘을 뺀다.
     */
    private val foldedLegacyV2Seats = setOf(576, 622)

    @Test
    fun `custom scenario uses complete live jurisdiction authority without a historical baseline`() {
        val cities=MapJson.loadFromClasspath("han-world-v3").cities.map { city ->
            SpatialSupplyCity(city.id,requireNotNull(city.provinceId),if(city.id==720) 77 else 0,city.physicalPlaceRef,city.routeNodeKey)
        }
        val custom=provider().network(990001,cities)
        val known=provider().network(1020,cities)
        assertEquals(known.provinceOwners.toList(),custom.provinceOwners.toList())
        assertTrue(custom.provinceOwners.any { it==77 })
        assertTrue(custom.provinceOwners.all { it==0 || it==77 })
        // 판을 박아 두면 지도 릴리스마다 살아 있는 城 집합과 어긋난다(2026-09-21 실측: 1,168 판에서
        // 城 1134 identity 불일치). 런타임이 하는 그대로 살아 있는 城 id 로 판을 고른다.
        val artifacts=opensamguk.infra.seed.HanWorldArtifactsResolver(Path("../.."))
            .resolve(cities.map { it.cityId }, emptyList())
        val selected=provider().network("han-world-v3",990001,cities,artifacts=artifacts)
        assertEquals(custom.provinceOwners.toList(),selected.provinceOwners.toList())
        assertFailsWith<IllegalArgumentException> {
            provider().network("han-world-v3",990001,cities.mapIndexed { i,c ->
                if(i==0)c.copy(routeNodeKey="invalid") else c },artifacts=artifacts)
        }
        assertFailsWith<IllegalStateException> { provider().network(990001,cities.dropLast(1)) }
        assertFailsWith<IllegalStateException> { provider().network(990001,emptyList()) }
        assertFailsWith<IllegalStateException> { provider().network(990001,cities+ cities.first()) }
    }

    @Test
    fun `reviewed active fallback policies are attached to the spatial network`() {
        val loader = HanSupplyDisconnectionPolicyLoader(
            mapper, ledgerPath, mapPath, runtimeMapPath, sourceLedgerPath,
        )
        val provider = HanSpatialSupplyProvider(mapper, mapPath, ownershipPath, loader)
        val liveCities = MapJson.loadFromClasspath("han").cities.filter { it.id !in foldedLegacyV2Seats }.mapNotNull { city ->
            city.provinceId?.let { SpatialSupplyCity(city.id, it, 0) }
        }

        val network = provider.network(1020, liveCities)

        assertEquals(
            opensamguk.logic.world.SupplyDisconnectionDecision.PROTECT_GEOMETRY_DEFECT,
            network.fallbackPolicies.getValue(47).decision,
        )
        assertTrue(164 !in network.fallbackPolicies, "unclassified rows stay runtime-protected without a guessed policy")
    }

    @Test
    fun `canonical Han topology exposes 1558 provinces and 4167 symmetric edges`() {
        val network = provider().network(1020, emptyList())

        // 2026-09-15: 수·진·관 거점 省 73 을 縣 省에서 떼어 배열 끝에 붙였다. 2026-09-16 平陰 省 1 이 더해져 1,594 다.
        // 2026-09-18 지리 재분할(GH #806): 郡 안 縣 경계를 城의 실제 위치로 다시 잘라 1,594 → 1,331 省.
        // 2026-09-20 한반도·만주 확장: 1,558개 구획, 대칭 인접 4,167개.
        // 2026-09-21 한반도·만주 임시 거점 정리(#848): 1,558 → 1,374 省.
        assertEquals(1_430, network.provinceOwners.size)  // 2026-09-23 결손 縣 56곳: 省 1,374 → 1,430
        assertEquals(1_430, network.provinceAdjacency.size)
        // 동명이지에 잘못 묶인 縣 4곳을 CHGIS 제자리로 되돌리면서 省 인접이 2간선 늘었다
        // (4,118 → 4,120). 거점 省이 이웃과 새 경계를 내어 4,274 다. han-tiles.json adjacency.county 실측값이다.
        // 2026-09-16 1098: 五原郡 九原·河陰이 南匈奴 땅에서 발자국을 받고 平陰 省이 생기며 4,275.
        // 2026-09-18 지리 재분할: han-tiles adjacency.county 실측 4,275 → 3,551.
        // 2026-09-21 #848: han-tiles adjacency.county 실측 3,660.
        // 2026-09-23 결손 縣 56곳이 제 省을 받아 han-tiles adjacency.county 실측 3,767.
        assertEquals(3_767, network.provinceAdjacency.sumOf(IntArray::size) / 2)
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
    fun `captured city territory matches the map API projection`() {
        val city = SpatialSupplyCity(cityId = 720, provinceIndex = 846, nationId = 77)
        val engine = provider().network(1020, listOf(city))
        val api = MapAdministrativeOwnership(mapper, mapPath, ownershipPath, allowlistPath)
            .project(1020.toString(), listOf(LiveCityOwnership(720, 846, 77)))

        assertEquals(api.provinceOccupancy.map { it.nationId }, engine.provinceOwners.toList())
        assertEquals(846, engine.cityProvinceIndices.getValue(720))
    }

    @Test
    fun `all 15 scenarios have owned mapped seats and supplied mapped capitals`() {
        val cityCoords = MapJson.loadFromClasspath("han").cities.filter { it.id !in foldedLegacyV2Seats }
        val cityProvinceById = cityCoords.mapNotNull { city ->
            city.provinceId?.let { city.id to it }
        }.toMap()
        val cityConst = ActiveWorldMap.requireVariant(mapOf("mapName" to "han"), emptyMap())
        val scenarioCodes = mapper.readTree(Path(ownershipPath).toFile()).path("scenarios")
            .map { it.path("scenarioCode").asInt() }
        val reviewedProvider = HanSpatialSupplyProvider(
            mapper,
            mapPath,
            ownershipPath,
            HanSupplyDisconnectionPolicyLoader(
                mapper, ledgerPath, mapPath, runtimeMapPath, sourceLedgerPath,
            ),
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
            val network = reviewedProvider.network(scenarioCode, liveCities)
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
            val evaluation = evaluateSupplyReachability(
                cities = ownedCities,
                capitals = capitals,
                cityConst = cityConst,
                spatialNetwork = network,
            )
            assertEquals(ownedCities.map { it.id }.sorted(), evaluation.rows.map { it.cityId })
            evaluation.rows.filter { it.verdict == SupplyReachabilityVerdict.SPATIAL_CUT_UPHELD }
                .forEach { row -> assertTrue(row.policy?.upholdsSpatialCut == true) }
            capitals.filter { it.capitalCityId in cityProvinceById }.forEach { capital ->
                assertTrue(
                    capital.capitalCityId in evaluation.suppliedCityIds,
                    "scenario $scenarioCode mapped capital ${capital.capitalCityId} is not supplied",
                )
            }
        }
    }

    @Test
    fun `captured jurisdiction updates every member province (R1 county level ownership)`() {
        val root = mapper.readTree(Path(mapPath).toFile())
        val lu = root.path("jurisdictionRecords").single { it.path("id").asText() == "87436" }
        val provinceIds = root.path("provinceRecords").map { it.path("id").asText() }
        val indices = lu.path("provinceIds").map { provinceIds.indexOf(it.asText()) }
        val network = provider().network(
            1020,
            listOf(SpatialSupplyCity(cityId = 720, provinceIndex = 846, nationId = 77)),
        )

        assertTrue(indices.size > 1, "capture fixture must cover multiple provinces")
        indices.forEach { index ->
            assertEquals(77, network.provinceOwners[index], "province $index")
        }
    }

    @Test
    fun `capture covers a split baseline province while static placement keeps it`() {
        val map = mapper.readTree(Path(mapPath).toFile())
        val provinces = map.path("provinceRecords")
        val jurisdiction = provinces[846].path("jurisdictionId").asText()
        val splitIndex = (0 until provinces.size()).first {
            it != 846 && provinces[it].path("jurisdictionId").asText() == jurisdiction
        }
        val splitId = provinces[splitIndex].path("id").asText()
        val ownership = mapper.readTree(Path(ownershipPath).toFile())
        val assignment = ownership.path("scenarios").single { it.path("scenarioCode").asInt() == 1020 }
            .path("assignments").single { it.path("provinceId").asText() == splitId }
        (assignment as com.fasterxml.jackson.databind.node.ObjectNode).put("ownerNationId", 88)
        val file = createTempFile("split-ownership", ".json")
        try {
            mapper.writeValue(file.toFile(), ownership)
            val provider = HanSpatialSupplyProvider(mapper, mapPath, file.toString())
            val baseline = provider.network(1020, emptyList()).provinceOwners.toList()
            assertEquals(88, baseline[splitIndex])
            // R1(ADR-LITE-052): live 점령은 심사 분할 칸도 함께 넘긴다.
            val captured = provider.network(1020, listOf(SpatialSupplyCity(720, 846, 77)))
            assertEquals(77, captured.provinceOwners[846])
            assertEquals(77, captured.provinceOwners[splitIndex])
            captured.provinceOwners[splitIndex] = 99
            assertEquals(baseline, provider.network(1020, emptyList()).provinceOwners.toList())
        } finally {
            file.deleteIfExists()
        }
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
