package opensamguk.engine.v2

import opensamguk.common.wire.CityTransport
import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.world.CalcCityDistance
import opensamguk.logic.world.*
import opensamguk.logic.v2.command.V2CityTransportArgs
import opensamguk.logic.v2.command.resolveImmediateCityTransportRoute
import opensamguk.infra.seed.HanStrategicTopologyJson
import java.nio.file.Path
import org.mockito.Mockito
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * OPENSAM-154 (v2 R5) — 수송 판정 + 핸들러 적용 경로. RNG 를 쓰지 않는다(두 함수 모두 `RandUtil` 인자 없음).
 */
class V2CityTransportRulesTest {

    private val full = V2CityLedgerEntry(gold = 100_000, rice = 100_000, garrison = 100_000)

    private fun decide(
        gold: Long = 0, rice: Long = 0, garrison: Int = 0,
        hop: Int? = 1, crew: Int = 2000, from: V2CityLedgerEntry = full,
    ) = transportDecision(gold, rice, garrison, hop, crew, from)

    // ── transportDecision — 순수 함수 ────────────────────────────────────────────────────────

    @Test
    fun `아무것도 지정하지 않으면 deny`() {
        assertEquals(V2TransportDecision.Denied("수송할 자원을 지정해야 합니다."), decide())
    }

    @Test
    fun `음수는 deny`() {
        assertEquals(V2TransportDecision.Denied("수송량은 음수일 수 없습니다."), decide(gold = -1))
    }

    @Test
    fun `인접 1홉이 아니면 deny - 0홉(같은 도시)도 2홉도 도달 불가도 막는다`() {
        val reason = "인접한 도시로만 수송할 수 있습니다."
        assertEquals(V2TransportDecision.Denied(reason), decide(gold = 100, hop = 0))
        assertEquals(V2TransportDecision.Denied(reason), decide(gold = 100, hop = 2))
        assertEquals(V2TransportDecision.Denied(reason), decide(gold = 100, hop = null))
    }

    /** 묘섭 원문 값 — "수송에 필요한 최소병사량은 2000명"(`:364`). */
    @Test
    fun `호송 병사 2000명 미만은 deny, 정확히 2000은 통과`() {
        assertIs<V2TransportDecision.Denied>(decide(gold = 100, crew = 1999))
        assertIs<V2TransportDecision.Applied>(decide(gold = 100, crew = 2000))
        assertEquals(2000, TRANSPORT_MIN_ESCORT_CREW)
    }

    /** 묘섭 원문 값 — 금·병량 각 5만(`:364`). 경계는 통과, +1은 deny. */
    @Test
    fun `금·병량 상한은 5만`() {
        assertIs<V2TransportDecision.Applied>(decide(gold = 50_000))
        assertIs<V2TransportDecision.Denied>(decide(gold = 50_001))
        assertIs<V2TransportDecision.Applied>(decide(rice = 50_000))
        assertIs<V2TransportDecision.Denied>(decide(rice = 50_001))
        assertEquals(50_000L, TRANSPORT_MAX_GOLD)
        assertEquals(50_000L, TRANSPORT_MAX_RICE)
    }

    /** 도시병사 상한은 **묘섭 미명시(U6)** — 임시로 금·병량과 같은 5만이며, 값이 바뀌어도 구조는 불변이다. */
    @Test
    fun `도시병사 상한은 임시로 5만이다 - 묘섭 미명시`() {
        assertIs<V2TransportDecision.Applied>(decide(garrison = 50_000))
        assertIs<V2TransportDecision.Denied>(decide(garrison = 50_001))
        assertEquals(50_000, TRANSPORT_MAX_GARRISON)
    }

    @Test
    fun `출발 도시 잔액이 모자라면 자원별 사유로 deny`() {
        val poor = V2CityLedgerEntry(gold = 10, rice = 10, garrison = 10)
        assertEquals(V2TransportDecision.Denied("도시의 금이 부족합니다."), decide(gold = 11, from = poor))
        assertEquals(V2TransportDecision.Denied("도시의 병량이 부족합니다."), decide(rice = 11, from = poor))
        assertEquals(V2TransportDecision.Denied("도시의 병사가 부족합니다."), decide(garrison = 11, from = poor))
        // 잔액과 정확히 같으면 통과한다.
        assertIs<V2TransportDecision.Applied>(decide(gold = 10, rice = 10, garrison = 10, from = poor))
    }

    @Test
    fun `결정성 - 같은 입력 같은 출력`() {
        assertEquals(decide(gold = 7, rice = 8, garrison = 9), decide(gold = 7, rice = 8, garrison = 9))
    }

    // ── handle — 월드 경로 ──────────────────────────────────────────────────────────────────

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")
    private lateinit var lastWorld: InMemoryTurnWorld
    private lateinit var lastRecorder: ChangeRecorder
    private lateinit var lastLedger: V2CityLedgerStore

    /** `CityConst.path`에서 실제로 인접한 두 도시 id — 골든 잠금 값이라 테스트가 지어내지 않는다. */
    private fun adjacentPair(): Pair<Int, Int> {
        for (a in 1..80) {
            for (b in 1..80) {
                if (a != b && CalcCityDistance.calcCityDistance(a, b) == 1) return a to b
            }
        }
        error("CityConst.path 에서 인접 도시 쌍을 찾지 못했다")
    }

    private fun handler(
        cityIds: List<Int>, crew: Int = 2000, nationId: Int = 1, mapName: String? = "che",
        loadTopology: () -> HanStrategicRouteProjection = { HanStrategicTopologyJson.loadFromDirectory(Path.of("../.."), "han-world-v3") },
    ): V2CityTransportHandler {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 3,
                    tickSeconds = 3600,
                    lastTurnTime = t0,
                    config = mapName?.let { mapOf("mapName" to it) }.orEmpty(),
                ),
                generals = listOf(
                    TurnGeneral(
                        id = 10, name = "관우", nationId = 1, cityId = cityIds.first(), troopId = 0,
                        stats = GeneralStats(80, 90, 70), experience = 0, dedication = 0,
                        officerLevel = 12, gold = 100, crew = crew, turnTime = t0,
                    ),
                ),
                nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 1000)),
                cities = cityIds.map { City(id = it, name = "성$it", nationId = nationId, level = 1) },
                worldId = opensamguk.common.world.WorldId(1),
            ),
        )
        val ledger = V2CityLedgerStore(Mockito.mock(NamedParameterJdbcTemplate::class.java))
        val recorder = ChangeRecorder()
        lastWorld = world; lastRecorder = recorder; lastLedger = ledger
        return V2CityTransportHandler(world, recorder, ledger, loadTopology)
    }

    private fun reasonOf(result: opensamguk.common.wire.TurnDaemonCommandResult) =
        (result as CommandLifecycleResult).reason

    @Test
    fun `인접 두 도시 사이 수송은 출발 감소·도착 증가를 같은 recorder 에 싣는다`() {
        val (a, b) = adjacentPair()
        val h = handler(listOf(a, b))
        lastLedger.adjust(lastWorld.worldId, ChangeRecorder(), a, goldDelta = 10_000, riceDelta = 5_000, garrisonDelta = 3_000)

        val result = h.handle(
            CityTransport(generalId = 10, fromCityId = a, toCityId = b, gold = 1_000, rice = 500, garrison = 300),
        )
        assertTrue(result.ok, reasonOf(result) ?: "")

        assertEquals(V2CityLedgerEntry(9_000, 4_500, 2_700), lastLedger.entry(lastWorld.worldId, a))
        assertEquals(V2CityLedgerEntry(1_000, 500, 300), lastLedger.entry(lastWorld.worldId, b))
        // 두 도시의 upsert 가 **같은** recorder 에 있다 = 같은 flush 트랜잭션.
        assertEquals(2, lastRecorder.cityLedgerV2Upserts().size)
        // 장수는 이동하지 않는다(묘섭 :366).
        assertEquals(a, lastWorld.getGeneralById(10)!!.cityId)
    }

    @Test
    fun `han world v3 노에서 역성 수송도 경로 pin 없이는 거절하고 원장을 보존한다`() {
        val h = handler(listOf(273, 781), mapName = "han-world-v3")
        lastLedger.adjust(lastWorld.worldId, ChangeRecorder(), 273, goldDelta = 10_000)

        val result = h.handle(
            CityTransport(generalId = 10, fromCityId = 273, toCityId = 781, gold = 100),
        )

        assertFalse(result.ok)
        assertEquals("TOPOLOGY_REVISION_REQUIRED", (result as CommandLifecycleResult).code)
        assertEquals(10_000L, lastLedger.entry(lastWorld.worldId, 273).gold)
        assertTrue(lastRecorder.cityLedgerV2Upserts().isEmpty())
    }

    @Test
    fun `real V3 Lu Licheng pinned route applies both ledgers and does not move escort`() {
        val load = { HanStrategicTopologyJson.loadFromDirectory(Path.of("../.."), "han-world-v3") }
        val route = assertIs<StrategicPathResult.Resolved>(resolveImmediateCityTransportRoute(
            V2CityTransportArgs(273, 781, 100, 0, 0, null), load,
        )).path
        assertEquals(listOf("land:45098", "land:45022"), route.nodeKeys)
        val h = handler(listOf(273, 781), mapName = "han-world-v3", loadTopology = load)
        lastLedger.adjust(lastWorld.worldId, ChangeRecorder(), 273, goldDelta = 1000, riceDelta = 1000, garrisonDelta = 1000)
        val result = h.handle(CityTransport(
            generalId = 10, fromCityId = 273, toCityId = 781, gold = 100, rice = 200, garrison = 300,
            topologyRevision = route.topologyRevision, routePathHash = route.pathHash,
        ))
        assertTrue(result.ok, reasonOf(result))
        assertEquals(V2CityLedgerEntry(900, 800, 700), lastLedger.entry(lastWorld.worldId, 273))
        assertEquals(V2CityLedgerEntry(100, 200, 300), lastLedger.entry(lastWorld.worldId, 781))
        assertEquals(2, lastRecorder.cityLedgerV2Upserts().size)
        assertEquals(273, lastWorld.getGeneralById(10)?.cityId)
    }

    @Test
    fun `legacy Han persisted V2 and che transports never require strategic artifacts or pins`() {
        listOf("han", "han-780-v1", "han-world-v2", "che").forEach { mapName ->
            val map = CityConstRegistry.of(mapName)
            val pair = map.all().keys.firstNotNullOf { from ->
                map.all().keys.firstOrNull { to -> CalcCityDistance.calcCityDistance(from, to, cityConst = map) == 1 }
                    ?.let { to -> from to to }
            }
            val h = handler(listOf(pair.first, pair.second), mapName = mapName,
                loadTopology = { error("legacy must not load topology") })
            lastLedger.adjust(lastWorld.worldId, ChangeRecorder(), pair.first, goldDelta = 1000)
            val result = h.handle(CityTransport(generalId = 10, fromCityId = pair.first, toCityId = pair.second, gold = 100))
            assertTrue(result.ok, "$mapName: ${reasonOf(result)}")
            assertEquals(900L, lastLedger.entry(lastWorld.worldId, pair.first).gold)
            assertEquals(100L, lastLedger.entry(lastWorld.worldId, pair.second).gold)
        }
    }

    @Test
    fun `V3 stale topology or changed path rejects without any ledger delta`() {
        val projection = testProjection()
        val route = assertIs<StrategicPathResult.Resolved>(resolveImmediateCityTransportRoute(
            V2CityTransportArgs(1, 2, 100, 0, 0, null), { projection },
        )).path
        listOf(
            "old" to route.pathHash to "TOPOLOGY_REVISION_STALE",
            route.topologyRevision to "old-path" to "ROUTE_PATH_HASH_STALE",
        ).forEach { (pins, code) ->
            val h = handler(listOf(1, 2), mapName = "han-world-v3", loadTopology = { projection })
            lastLedger.adjust(lastWorld.worldId, ChangeRecorder(), 1, goldDelta = 1000)
            val result = assertIs<CommandLifecycleResult>(h.handle(CityTransport(
                generalId = 10, fromCityId = 1, toCityId = 2, gold = 100,
                topologyRevision = pins.first, routePathHash = pins.second,
            )))
            assertEquals(code, result.code)
            assertFalse(result.ok)
            assertEquals(1000L, lastLedger.entry(lastWorld.worldId, 1).gold)
            assertEquals(0L, lastLedger.entry(lastWorld.worldId, 2).gold)
            assertTrue(lastRecorder.cityLedgerV2Upserts().isEmpty())
        }
    }

    @Test
    fun `V3 multi-hop land and ferry cannot become instantaneous transfer`() {
        listOf(
            testProjection(multiHop = true) to "ROUTE_REQUIRES_MULTI_TURN",
            testProjection(mode = TraversalMode.FERRY) to "TRANSPORT_MODE_UNSUPPORTED",
        ).forEach { (projection, code) ->
            val route = assertIs<StrategicPathResult.Resolved>(resolveImmediateCityTransportRoute(
                V2CityTransportArgs(1, 2, 100, 0, 0, null), { projection },
            )).path
            val h = handler(listOf(1, 2), mapName = "han-world-v3", loadTopology = { projection })
            lastLedger.adjust(lastWorld.worldId, ChangeRecorder(), 1, goldDelta = 1000)
            val result = assertIs<CommandLifecycleResult>(h.handle(CityTransport(
                generalId = 10, fromCityId = 1, toCityId = 2, gold = 100,
                topologyRevision = route.topologyRevision, routePathHash = route.pathHash,
            )))
            assertEquals(code, result.code)
            assertFalse(result.ok)
            assertEquals(1000L, lastLedger.entry(lastWorld.worldId, 1).gold)
            assertTrue(lastRecorder.cityLedgerV2Upserts().isEmpty())
        }
    }

    @Test
    fun `missing V3 artifacts and unknown bindings cannot fall back to adjacent CityConst`() {
        val incomplete = testProjection().let { HanStrategicRouteProjection(it.topology, it.bindingsByCityId.values.take(1)) }
        val cases: List<Pair<() -> HanStrategicRouteProjection, String>> = listOf(
            { error("missing topology") } to "TOPOLOGY_STATE_INVALID",
            { incomplete } to "UNKNOWN_NODE",
        )
        cases.forEach { (load, code) ->
            val h = handler(listOf(1, 2), mapName = "han-world-v3", loadTopology = load)
            lastLedger.adjust(lastWorld.worldId, ChangeRecorder(), 1, goldDelta = 1000)
            val result = assertIs<CommandLifecycleResult>(h.handle(CityTransport(
                generalId = 10, fromCityId = 1, toCityId = 2, gold = 100,
                topologyRevision = "test-v3", routePathHash = "old",
            )))
            assertEquals(code, result.code)
            assertFalse(result.ok)
            assertEquals(1000L, lastLedger.entry(lastWorld.worldId, 1).gold)
            assertTrue(lastRecorder.cityLedgerV2Upserts().isEmpty())
        }
    }

    private fun testProjection(multiHop: Boolean = false, mode: TraversalMode = TraversalMode.LAND): HanStrategicRouteProjection {
        fun edge(id: String, from: String, to: String) = TraversalEdge(
            id, StrategicNodeRef.LandProvince(from), StrategicNodeRef.LandProvince(to), mode, false,
            1, 1000, RiskBand.LOW, SeasonalAvailability.ALWAYS, true, listOf("reviewed:test"), EvidenceConfidence.EXACT,
        )
        return HanStrategicRouteProjection(
            StrategicTopologySnapshot("test-v3", setOf("a", "b", "c"), emptyList(),
                if (multiHop) listOf(edge("ac", "a", "c"), edge("cb", "c", "b")) else listOf(edge("ab", "a", "b")),
                emptyList(), mapOf("fixture" to "abc")),
            listOf(HanStrategicRouteBinding(1, "route:a", "physical:a", "a"),
                HanStrategicRouteBinding(2, "route:b", "physical:b", "b")),
        )
    }

    @Test
    fun `인접하지 않은 도시로는 deny 이고 원장은 그대로다`() {
        val (a, b) = adjacentPair()
        val far = (1..80).first { it != a && it != b && CalcCityDistance.calcCityDistance(a, it) != 1 }
        val h = handler(listOf(a, b, far))
        lastLedger.adjust(lastWorld.worldId, ChangeRecorder(), a, goldDelta = 10_000)

        val result = h.handle(CityTransport(generalId = 10, fromCityId = a, toCityId = far, gold = 100))
        assertFalse(result.ok)
        assertEquals("인접한 도시로만 수송할 수 있습니다.", reasonOf(result))
        assertEquals(10_000L, lastLedger.entry(lastWorld.worldId, a).gold)
        assertTrue(lastRecorder.cityLedgerV2Upserts().isEmpty())
    }

    @Test
    fun `missing or invalid active map returns terminal route denial`() {
        val (a, b) = adjacentPair()

        listOf<String?>(null, "not-a-map").forEach { mapName ->
            val result = handler(listOf(a, b), mapName = mapName)
                .handle(CityTransport(generalId = 10, fromCityId = a, toCityId = b, gold = 100))

            val lifecycle = assertIs<CommandLifecycleResult>(result)
            assertFalse(lifecycle.ok)
            assertEquals("ROUTE_NOT_ADJACENT", lifecycle.code)
            assertEquals("인접한 도시로만 수송할 수 있습니다.", lifecycle.reason)
        }
    }

    @Test
    fun `장수가 없는 도시에서 출발하면 deny`() {
        val (a, b) = adjacentPair()
        val h = handler(listOf(a, b))
        val result = h.handle(CityTransport(generalId = 10, fromCityId = b, toCityId = a, gold = 100))
        assertFalse(result.ok)
        assertEquals("장수가 있는 도시에서만 수송할 수 있습니다.", reasonOf(result))
    }

    @Test
    fun `타국 도시로는 deny`() {
        val (a, b) = adjacentPair()
        val h = handler(listOf(a, b), nationId = 2) // 장수 국가는 1, 도시는 2
        val result = h.handle(CityTransport(generalId = 10, fromCityId = a, toCityId = b, gold = 100))
        assertFalse(result.ok)
        assertEquals("자국 도시끼리만 수송할 수 있습니다.", reasonOf(result))
    }

    @Test
    fun `호송 병사가 모자라면 deny 이고 원장은 그대로다`() {
        val (a, b) = adjacentPair()
        val h = handler(listOf(a, b), crew = 1_999)
        lastLedger.adjust(lastWorld.worldId, ChangeRecorder(), a, goldDelta = 10_000)

        val result = h.handle(CityTransport(generalId = 10, fromCityId = a, toCityId = b, gold = 100))
        assertFalse(result.ok)
        assertEquals(10_000L, lastLedger.entry(lastWorld.worldId, a).gold)
        assertTrue(lastRecorder.cityLedgerV2Upserts().isEmpty())
    }
}
