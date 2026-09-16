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
        assertEquals(1594, network.provinceOwners.size)
        // 省 1,593(수·진·관 거점 省 73 포함) · 郡縣 인접 4,274(han-tiles adjacency.county 실측) 중 물을 건너는
        // 60 간선이 v3 에서 빠져 4,214 다. v2 는 그 걸러내기가 없어 아래에서 4,274 그대로다.
        assertEquals(4215, network.provinceAdjacency.sumOf(IntArray::size) / 2)  // 2026-09-16 1098: 五原郡 본토 +1
        assertNotNull(network.strategicSupply)
        assertEquals(4275, provider.network("han-world-v2", 1020, emptyList()).provinceAdjacency.sumOf(IntArray::size) / 2)  // 2026-09-16 1098 과 같은 han-tiles
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
            assertEquals(KNOWN_MISBOUND_DONOR_CUTS.filterValues { code in it }.keys, before - after, "new cut in $code")
        }
    }

    private companion object {
        /**
         * 기존 결함에서 온 절단 — 새로 생기면 안 되고, 결함이 고쳐지면 이 표에서 지워야 한다(정확히 같아야 통과).
         *
         * 2026-09-16 1098: 비었다. 1097 판의 유일한 예외였던 1062 孟津(1020·1021·1040·1041·1050·1060)은
         * 城 56 「하음」이 郡國志 五原郡 河陰 식별자로 河南尹 河陰縣 省(82880)을 차지한 탓이었다. 56 을 사료 자리
         * (豐州西南, 바오터우)로 옮기고 옛 발자국을 河南尹 平陰縣(82879, 城 1098)에 넘기자, 孟津의 기증 省이 河南尹 城의
         * 땅이 되어 이 절단이 사라졌다 — 이 표가 비지 않았다면 테스트가 「new cut in 1020 ==> expected [1062]」로
         * 빨개진다(실측으로 확인).
         */
        val KNOWN_MISBOUND_DONOR_CUTS = emptyMap<Int, Set<Int>>()
    }
}
