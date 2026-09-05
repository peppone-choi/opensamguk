package opensamguk.logic.v2.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import opensamguk.logic.world.PathDenialCode
import opensamguk.logic.world.ResolvedStrategicPath
import opensamguk.logic.world.StrategicPathResult
import opensamguk.logic.world.TraversalMode

class V2CommandDecisionTest {
    private val pinned = V2CityTransportArgs(1, 2, 100, 0, 0, 9, "v3:abc", "path:123")
    private val path = ResolvedStrategicPath(
        listOf("land:45098", "land:45022"), listOf("dry:lu-li"), listOf(TraversalMode.LAND),
        1, 1000, "v3:abc", "topology:abc", "path:123",
    )
    private val strategicContext = V2CityTransportContext(
        1, 1, 2000, 1, 1, null, 1000, 1000, 1000,
        requiresStrategicRoute = true, strategicRoute = StrategicPathResult.Resolved(path),
    )

    @Test
    fun `V3 accepts only the pinned strategic route without consulting legacy distance`() {
        assertIs<V2CityTransportDecision.Applied>(decideCityTransport(pinned, strategicContext))
        assertIs<V2CityTransportDecision.Applied>(decideCityTransport(pinned, strategicContext.copy(hopDistance = 99)))
    }

    @Test
    fun `V3 rejects missing and stale pins even if CityConst says adjacent`() {
        listOf(
            pinned.copy(topologyRevision = null) to "TOPOLOGY_REVISION_REQUIRED",
            pinned.copy(routePathHash = " ") to "ROUTE_PATH_HASH_REQUIRED",
            pinned.copy(topologyRevision = "old") to "TOPOLOGY_REVISION_STALE",
            pinned.copy(routePathHash = "different-path") to "ROUTE_PATH_HASH_STALE",
        ).forEach { (args, code) ->
            assertEquals(code, assertIs<V2CityTransportDecision.Denied>(
                decideCityTransport(args, strategicContext.copy(hopDistance = 1)),
            ).code)
        }
    }

    @Test
    fun `V3 missing topology unknown node and disconnected path never fall back to CityConst`() {
        assertEquals("TOPOLOGY_STATE_INVALID", assertIs<V2CityTransportDecision.Denied>(
            decideCityTransport(pinned, strategicContext.copy(hopDistance = 1, strategicRoute = null)),
        ).code)
        PathDenialCode.entries.forEach { code ->
            assertEquals(code.name, assertIs<V2CityTransportDecision.Denied>(decideCityTransport(
                pinned, strategicContext.copy(hopDistance = 1, strategicRoute = StrategicPathResult.Denied(code)),
            )).code)
        }
    }

    @Test
    fun `strategic route denial presents actionable Korean reasons instead of raw codes`() {
        mapOf(
            PathDenialCode.NO_LAND_CONNECTION to "연결된 육로가 없습니다.",
            PathDenialCode.RIVER_CROSSING_REQUIRED to "강을 건널 수 있는 검증된 통과점이 필요합니다.",
            PathDenialCode.NO_EMBARK_POINT to "승선 또는 하선할 수 있는 지점이 없습니다.",
            PathDenialCode.NO_TRANSPORT_CAPACITY to "수송 경로의 가용 용량이 부족합니다.",
            PathDenialCode.WATERWAY_BLOCKED to "물길이 봉쇄되었거나 현재 통행할 수 없습니다.",
            PathDenialCode.TOPOLOGY_REVISION_STALE to "지도 정보가 변경되었습니다. 수송 경로를 다시 확인하세요.",
            PathDenialCode.TOPOLOGY_STATE_INVALID to "수송 지형 정보 검증에 실패했습니다.",
            PathDenialCode.UNKNOWN_NODE to "출발지 또는 도착지가 수송 지도에 연결되지 않았습니다.",
        ).forEach { (code, reason) ->
            val denied = assertIs<V2CityTransportDecision.Denied>(decideCityTransport(
                pinned, strategicContext.copy(strategicRoute = StrategicPathResult.Denied(code)),
            ))
            assertEquals(code.name, denied.code)
            assertEquals(reason, denied.reason)
        }
    }

    @Test
    fun `multi-hop land and every crossing or water mode require future scheduler`() {
        val multi = path.copy(nodeKeys = listOf("land:a", "land:b", "land:c"),
            edgeIds = listOf("ab", "bc"), modes = listOf(TraversalMode.LAND, TraversalMode.LAND))
        assertEquals("ROUTE_REQUIRES_MULTI_TURN", assertIs<V2CityTransportDecision.Denied>(decideCityTransport(
            pinned, strategicContext.copy(strategicRoute = StrategicPathResult.Resolved(multi)),
        )).code)
        TraversalMode.entries.filter { it != TraversalMode.LAND }.forEach { mode ->
            assertEquals("TRANSPORT_MODE_UNSUPPORTED", assertIs<V2CityTransportDecision.Denied>(decideCityTransport(
                pinned, strategicContext.copy(strategicRoute = StrategicPathResult.Resolved(path.copy(modes = listOf(mode)))),
            )).code)
        }
    }

    @Test
    fun `V3 route success still enforces escort caps and ledger balance`() {
        listOf(
            pinned to strategicContext.copy(escortCrew = 1999) to "ESCORT_INSUFFICIENT",
            pinned.copy(gold = 50001) to strategicContext to "TRANSPORT_GOLD_LIMIT",
            pinned to strategicContext.copy(fromGold = 99) to "CITY_GOLD_INSUFFICIENT",
            pinned to strategicContext.copy(toNationId = 2) to "CITY_AUTHORITY_DENIED",
        ).forEach { (case, code) ->
            assertEquals(code, assertIs<V2CityTransportDecision.Denied>(decideCityTransport(case.first, case.second)).code)
        }
    }
    @Test
    fun `recruit context denies a missing actor with a stable reason`() {
        val decision = decideGarrisonRecruit(
            V2GarrisonRecruitArgs(cityId = 5, amount = 100),
            V2GarrisonRecruitContext.missingGeneral(),
        )

        val denied = assertIs<V2GarrisonRecruitDecision.Denied>(decision)
        assertEquals("GENERAL_NOT_FOUND", denied.code)
        assertEquals("장수를 찾을 수 없습니다.", denied.reason)
    }

    @Test
    fun `recruit context evaluates ownership population and ledger balance`() {
        val args = V2GarrisonRecruitArgs(cityId = 5, amount = 100)
        val context = V2GarrisonRecruitContext(
            generalCityId = 5,
            generalNationId = 1,
            leadership = 80,
            cityNationId = 1,
            cityPopulation = 31_000,
            cityTrust = 80.0,
            ledgerGold = 8,
        )

        val denied = assertIs<V2GarrisonRecruitDecision.Denied>(decideGarrisonRecruit(args, context))

        assertEquals("CITY_GOLD_INSUFFICIENT", denied.code)
        assertEquals("도시의 금이 부족합니다.", denied.reason)
    }

    @Test
    fun `transport context evaluates adjacency and ledger balance`() {
        val args = V2CityTransportArgs(1, 2, 100, 0, 0, 7)
        val context = V2CityTransportContext(
            generalCityId = 1,
            generalNationId = 1,
            escortCrew = 2_000,
            fromNationId = 1,
            toNationId = 1,
            hopDistance = 2,
            fromGold = 1_000,
            fromRice = 0,
            fromGarrison = 0,
        )

        val denied = assertIs<V2CityTransportDecision.Denied>(decideCityTransport(args, context))

        assertEquals("ROUTE_NOT_ADJACENT", denied.code)
        assertEquals("인접한 도시로만 수송할 수 있습니다.", denied.reason)
    }
}
