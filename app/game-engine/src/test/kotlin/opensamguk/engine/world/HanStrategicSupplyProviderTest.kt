package opensamguk.engine.world

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.seed.HanStrategicTopologyJson
import opensamguk.infra.seed.MapJson
import opensamguk.infra.seed.ScenarioJson
import opensamguk.logic.world.*
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
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
        // 省 1,593(수·진·관 거점 省 73 포함) · 郡縣 인접 4,274(han-tiles adjacency.county 실측) 중 물을 건너는
        // 60 간선이 v3 에서 빠져 4,214 다. v2 는 그 걸러내기가 없어 아래에서 4,274 그대로다.
        // 2026-09-18 지리 재분할(GH #806) 실측: 縣 인접 3,551 중 물을 건너는 51 간선이 v3 에서 빠져 3,500 (앞 판 4,215).
        // 확장 후 전체 4,167개 중 물을 건너는 51개를 제외한 마른땅 연결 4,116개.
        // 2026-09-21 #848: 縣 인접 3,660 중 물을 건너는 51 간선을 뺀 마른땅 3,609.
        // 2026-09-23 미해독 3행 제외·결손 縣 223곳 추가: 縣 인접 4,296 중 물 경계 52간선을 뺀 마른땅 4,244.
        assertEquals(4284, network.provinceAdjacency.sumOf(IntArray::size) / 2)
        assertNotNull(network.strategicSupply)
        assertEquals(4337, provider.network("han-world-v2", 1020, emptyList()).provinceAdjacency.sumOf(IntArray::size) / 2)  // 같은 타일의 전체 접경
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

    @Test fun `15 scenario road graph only removes supply reachability from the raw graph`() {
        val scenarios = mapper.readTree(Path.of("../../data/map/han-scenario-province-ownership-v1.json").toFile())
            .path("scenarios").map { it.path("scenarioCode").asInt() }
        assertEquals(15, scenarios.size)
        val cityConst = ActiveWorldMap.requireVariant(mapOf("mapName" to "han-world-v3"), emptyMap())
        val cuts = linkedMapOf<Int, Pair<Int, String>>()
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
            assertEquals(emptySet(), after - before, "road graph created supply reachability in $code")
            val sortedCuts = (before - after).sorted().joinToString(",")
            cuts[code] = (before - after).size to HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(sortedCuts.toByteArray(Charsets.UTF_8))
            )
        }
        assertEquals(mapOf(
            1010 to (23 to "63bf89266f82429aafa50cec6a947fe855f57cc35b074f894d8f65aa649a0f0a"),
            1020 to (78 to "6660d85aa2d5fc5b70a17abe2f10b77057511cf69c556868b07afc7a5461db47"),
            1021 to (94 to "35184194578392b77d601352485551c0d7ba04004635f4351a85172e2e014480"),
            1030 to (240 to "48ef39af94a35844895e64b35354b63a76e9ea35aae315c1a3967eeb61c785f2"),
            1031 to (309 to "46da6fa2bc70fcf0aca3ca20a3b68be406c99950c549704dd277de1b629f7ec2"),
            1040 to (218 to "3ab373db9cb515797f2a6bed860e51b7ff9f2530a61512c547f0d58b9274b055"),
            1041 to (312 to "149ca9180335a53cfc270144647c8c812a75d169e9453165658ac10003bffd2b"),
            1050 to (225 to "b70bf1afebe7b03bdefc1002e161f54b33a6f54d2233ea233688238bcac84e4f"),
            1060 to (229 to "b5cf5d2f8311c7112d83fb6ee5cacc1020dc9449c37fbf94a46b59777becaf1a"),
            1070 to (151 to "cddb1a844871ec1c1a9ec3c52d1885723123c793635072f8ea5cd67bc806cd86"),
            1080 to (195 to "2e70f0f1e420847bde680356b990c7936a51ba5906de1c1c2df4de8bca3ce08d"),
            1090 to (191 to "00fd0f72a50c82b1abc0c4df461797ee435e70792e22175a5f367aea05abcdac"),
            1100 to (931 to "9ee7af6041dfd2b35a263e53a1d52c2184bff1104bedd30325872fcf031cb16c"),
            1110 to (902 to "017169f1f5b71b91cbe1950dddc16e905e3c1ec5ccf6c9ed61d9ea73f4f8458e"),
            1120 to (78 to "f5ea5e58b39036e749600235df16b1fa674b919fed44a4aee6ba8f2aed075102"),
        ), cuts, "scenario road graph cuts changed")
    }
}
