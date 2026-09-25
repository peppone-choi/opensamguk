package opensamguk.engine.campaign

import java.io.File
import java.time.Instant
import kotlin.test.*
import opensamguk.common.wire.TurnDaemonCommand.ImmediateInput
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.logic.domestic.CountyIndicators
import opensamguk.logic.domestic.CountyMonthly
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.input.*
import opensamguk.logic.renown.RenownAssessment
import opensamguk.logic.renown.RenownEntry
import opensamguk.logic.renown.RenownEventKind
import opensamguk.logic.renown.RenownEventSource
import opensamguk.logic.renown.RenownEvents
import opensamguk.logic.world.*

/**
 * 통합 배선: 내정 스트림의 치적 사건(縣令 카드 주인) → 기록 스트림의 월단평 치적. 기록 스트림의 치적 창(발령 관할
 * 장수)과 한 경계에서 함께 돌아도 한 장수는 한 달에 치적 한 건이다. 월 경계 순서는 TurnRunService 와 같다:
 * 치적 창 닫기 → (월간 사건) → 내정 경계 → 월단평 → 치적 창 열기. DB 없음.
 */
class GovernanceMeritWiringTest {
    private val pin = "a".repeat(64)
    private val a = StrategicNodeRef.LandProvince("A")
    private val b = StrategicNodeRef.LandProvince("B")
    private val topology = StrategicTopologySnapshot("qa", setOf("A", "B"), emptyList(), listOf(
        TraversalEdge("ab", a, b, TraversalMode.LAND, false, 1, 10, RiskBand.LOW, SeasonalAvailability.ALWAYS,
            sourceRefs = listOf("qa:ab"), confidence = EvidenceConfidence.REVIEWED)), emptyList(),
        mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
    private val metrics = LandMarchMetricSnapshot(topology, pin, listOf(LandMarchEdgeMetric("ab", 40, 40)))
    private val curve = RenownAssessment.CANON

    private fun context(world: InMemoryTurnWorld, recorder: ChangeRecorder) = DomesticContext(
        geography = CountyGeography(listOf(CountyPlace(10, "甲郡", "갑군", "j10"), CountyPlace(11, "甲郡", "갑군", "j11"))),
        topology = topology, metrics = metrics, merit = GovernanceMeritRenownSink(world, recorder))

    private fun general(id: Int, node: String, human: Boolean = false, meta: Map<String, Any?> = emptyMap()) = TurnGeneral(
        id = id, name = "G$id", nationId = 1, cityId = if (node == "A") 10 else 11, troopId = 0,
        stats = GeneralStats(60, 60, 60, 80, 60), experience = 0, dedication = 0, officerLevel = if (human) 12 else 0,
        userId = if (human) "42" else null, npcState = if (human) 0 else 2, turnTime = Instant.EPOCH, meta = meta)

    private fun county(id: Int) = City(id, "縣$id", 1, 1, population = 50_000,
        populationMax = 100_000, agriculture = 1000, agricultureMax = 5000, commerce = 1000, commerceMax = 5000, security = 500,
        securityMax = 1000, defence = 500, defenceMax = 1000, wall = 500, wallMax = 1000,
        meta = mapOf("trust" to 80.0, CountyWarehouse.META_KEY to CountyWarehouse(id, 0, opensamguk.logic.economy.Resources()).toMetaValue()))

    /** G1: 사람 주공(카드 5 = G3 의 주인). [holder] 면 縣 10 발령 관할 장수이기도 하다. */
    private fun world(holder: Boolean): InMemoryTurnWorld {
        val lordMeta = mapOf("hwihaLord" to true,
            PersonPolicyState.META_KEY to PersonPolicyState(30, false, "synthetic-test", "1", 1).toMetaValue()) +
            if (holder) mapOf(CountyAssignment.META_KEY to CountyAssignment("d-10", 1, 1, 10).toMetaValue()) else emptyMap()
        val positions = listOf(1 to a, 2 to a, 3 to b).fold(GeneralPositionSnapshot("qa", topology.contentHash, setOf("A", "B"), emptySet())) { s, (id, node) ->
            s.withState(GeneralPositionState("qa", topology.contentHash, id, node, 1)) }
        return InMemoryTurnWorld(WorldSnapshot(worldId = WorldId(1),
            state = TurnWorldState(1, 200, 1, 3600, Instant.EPOCH, currentPhase = 1,
                config = mapOf("ruleProfile" to "HWIHA", "mapName" to "han-world-v3"),
                meta = mapOf(LandPassageState.META_KEY to LandPassageState.initialMetaValue(topology),
                    MarchReactions.META_KEY to MarchReactions.Empty.toMetaValue())),
            generals = listOf(general(1, "A", human = true, meta = lordMeta), general(2, "A", meta = mapOf("hwihaLord" to false)),
                general(3, "B", meta = mapOf("hwihaLord" to false))),
            nations = listOf(Nation(1, "N1", "#000", capitalCityId = 10), Nation(2, "N2", "#fff", capitalCityId = 11)),
            cities = listOf(county(10), county(11)),
            retainers = listOf(Retainer(4, 1, "EXISTING", 2, "G2", "lieutenant"), Retainer(5, 1, "EXISTING", 3, "G3", "staff")),
            generalPositionSnapshot = positions, cityLandProvinceById = mapOf(10 to "A", 11 to "B"), administrativeCountyIds = setOf(10, 11)))
    }

    private fun entries(world: InMemoryTurnWorld, id: Int) = RenownEvents.entries(world.getGeneralById(id)!!.meta)
    private fun renown(world: InMemoryTurnWorld, id: Int) = PersonPolicyState.read(world.getGeneralById(id)!!.meta)!!.renownCapacity

    /** TurnRunService 월 경계 순서 그대로(월간 사건은 없다). */
    private fun monthBoundary(world: InMemoryTurnWorld, recorder: ChangeRecorder, context: DomesticContext, year: Int, month: Int):
        Pair<List<Int>, DomesticBoundary.Outcome> {
        val closed = CountyMeritWindow(world, recorder).close(year, month)
        world.setCurrentDate(year, month, 1)
        val outcome = checkNotNull(DomesticBoundary(world, recorder, context).run())
        MonthlyAssessment(world, recorder, curve).assess(year, month)
        CountyMeritWindow(world, recorder).open(year, month)
        return closed to outcome
    }

    /**
     * G3(카드 5)를 [countyId] 縣令으로 배치하고 부임시킨 뒤 200-02 경계를 넘는다(내정: 첫 달 기록, 기록: 창 열기).
     * 그 달 동안 두 縣 의 전답이 상한의 10% 오른다 — 두 경로 모두의 문턱을 넘는다.
     */
    private fun seatMagistrate(world: InMemoryTurnWorld, recorder: ChangeRecorder, context: DomesticContext, countyId: Int) {
        val placed = CourtHandler(world, recorder, context).handle(ImmediateInput("req-seat", 1, 42, "placement.assign",
            """{"cardId":5,"post":"MAGISTRATE","countyId":$countyId}"""))
        assertTrue(placed.ok, "배치 접수: $placed")
        DomesticTurn(world, recorder, context).beforeMovement(3)
        PlacementMarchTurn(world, recorder, topology, metrics).onTurn(3)
        monthBoundary(world, recorder, context, 200, 2)
        assertEquals("0200-02", CountyMonthly.read(world.getCityById(countyId)!!.meta)!!.stamp)
        for (id in listOf(10, 11)) {
            val city = world.getCityById(id)!!
            world.applyCityDirtyFree(city.copy(agriculture = city.agriculture + 500))
        }
    }

    @Test fun `merit stamp is the month the indicators rose, not the month compared`() {
        assertEquals("0200-02", GovernanceMeritRenownSink.meritStamp("0200-03"))
        assertEquals("0199-12", GovernanceMeritRenownSink.meritStamp("0200-01"))
        assertNull(GovernanceMeritRenownSink.meritStamp("0000-01"))
        assertNull(GovernanceMeritRenownSink.meritStamp("garbage"), "월 경계에서 던지지 않는다")
    }

    @Test fun `the sink applies the records merit threshold to domestic events`() {
        val world = world(holder = false); val recorder = ChangeRecorder()
        val sink = GovernanceMeritRenownSink(world, recorder)
        val base = CountyIndicators(50_000, 1000, 1000, 500, 80, 500, 500)
        fun event(current: CountyIndicators) = GovernanceMeritEvent(1, 3, 5, 10, "0200-03", base, current,
            current.risenSince(base))
        // 치안·민심·방비만 오르거나 전답이 상한(5000)의 2% 미만(99)이면 치적이 아니다.
        sink.onCountyIndicatorsRose(event(base.copy(security = 600, trust = 90, wall = 600)))
        sink.onCountyIndicatorsRose(event(base.copy(agriculture = 1099)))
        assertTrue(entries(world, 1).isEmpty())
        sink.onCountyIndicatorsRose(event(base.copy(agriculture = 1100)))
        assertEquals(listOf(RenownEntry(RenownEventKind.DOMESTIC_MERIT, "0200-02", RenownEventSource.COUNTY_INDICATOR_RISE)),
            entries(world, 1))
        assertEquals(setOf(1), recorder.dirtyGeneralIds(), "쓰기는 ChangeRecorder 경로로 간다")
    }

    @Test fun `domestic magistrate merit reaches the card owner's renown tally and the same boundary's assessment`() {
        val world = world(holder = false); val recorder = ChangeRecorder(); val context = context(world, recorder)
        seatMagistrate(world, recorder, context, 10)
        val (closed, outcome) = monthBoundary(world, recorder, context, 200, 3)
        assertTrue(closed.isEmpty(), "발령 관할 장수가 없으니 기록 스트림 창은 아무도 치적을 주지 않는다")
        assertEquals(1, outcome.meritEvents)
        // 같은 경계의 월단평이 0200-02 치적을 적용하고 집계에서 뺐다.
        assertEquals(30 + curve.domesticMerit, renown(world, 1))
        assertTrue(entries(world, 1).isEmpty())
        val logged = world.peekLogs().filter { it.eventKind == RecordKind.RENOWN_EVENT && it.generalId == 1 }
        assertEquals(1, logged.size)
        assertEquals(mapOf("kind" to "domesticMerit", "source" to "COUNTY_INDICATOR_RISE", "stamp" to "0200-02"),
            logged.single().meta!![RecordKind.REFS_META_KEY])
        assertTrue(entries(world, 3).isEmpty(), "카드 인물이 아니라 카드 주인이 치적을 받는다")
    }

    @Test fun `a holder who also owns the seated magistrate card gets one merit per month from both paths`() {
        // 縣 10 은 G1 의 발령 관할이라 카드는 옆 縣 11 에 앉힌다(발령 관할 縣 에는 縣令 카드를 둘 수 없다).
        val world = world(holder = true); val recorder = ChangeRecorder(); val context = context(world, recorder)
        seatMagistrate(world, recorder, context, 11)
        val (closed, outcome) = monthBoundary(world, recorder, context, 200, 3)
        assertEquals(listOf(1), closed, "기록 스트림 창이 먼저 0200-02 치적을 쌓는다")
        assertEquals(1, outcome.meritEvents, "내정 경로도 사건을 냈다")
        val logged = world.peekLogs().filter { it.eventKind == RecordKind.RENOWN_EVENT && it.generalId == 1 }
        assertEquals(1, logged.size, "두 경로가 같은 달 같은 종류라 한 건으로 접힌다: $logged")
        assertEquals(30 + curve.domesticMerit, renown(world, 1), "치적은 한 번만 적용된다")
    }

    @Test fun `monthly natural growth after merit closes cannot earn the magistrate merit`() {
        val world = world(holder = false); val recorder = ChangeRecorder(); val context = context(world, recorder)
        seatMagistrate(world, recorder, context, 10)
        monthBoundary(world, recorder, context, 200, 3)
        val boundary = DomesticBoundary(world, recorder, context)
        assertEquals(0, boundary.closeMonthlyMerit(200, 4), "no direct work in March")
        val before = world.getCityById(10)!!
        world.applyCityDirtyFree(before.copy(agriculture = before.agriculture + 500)) // monthly natural event
        world.setCurrentDate(200, 4, 1)
        boundary.run(meritClosedBeforeMonthlyEvents = true)
        assertEquals(0, boundary.closeMonthlyMerit(200, 5), "April baseline opened after natural growth")
    }

    @Test fun `production daemon wires the domestic merit sink to the renown recorder on the shared recorder`() {
        val source = listOf(
            File("src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt"),
            File("app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("DaemonLoopConfig.kt source not found from ${File(".").absolutePath}")
        assertTrue(source.contains("merit = opensamguk.engine.campaign.GovernanceMeritRenownSink(world, recorder)"),
            "HWIHA 내정 문맥의 치적 사건이 버려지고 있다(GovernanceMeritSink.NONE)")
    }
}
