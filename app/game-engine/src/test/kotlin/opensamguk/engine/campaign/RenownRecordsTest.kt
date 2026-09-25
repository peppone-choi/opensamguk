package opensamguk.engine.campaign

import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.renown.RenownAssessment
import opensamguk.logic.renown.RenownEntry
import opensamguk.logic.renown.RenownEventKind
import opensamguk.logic.renown.RenownEventSource
import opensamguk.logic.renown.RenownEvents
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef

/** 월단평 사건 원천(이탈·치적·점령/조우 훅)과 월단평 적용·발표·기록. */
class RenownRecordsTest {
    private val curve = RenownAssessment.CANON
    private val HASH = "b".repeat(64)

    private fun policy(renown: Int) = PersonPolicyState(renown, false, "synthetic-test", "1", 1).toMetaValue()

    private fun person(id: Int, nationId: Int = 1, renown: Int = 30, extra: Map<String, Any?> = emptyMap()) =
        TurnGeneral(id = id, name = "G$id", nationId = nationId, cityId = 10, userId = "4$id", npcState = 2, troopId = 0,
            stats = GeneralStats(70, 70, 70), experience = 0, dedication = 0, officerLevel = 0, gold = 0, rice = 0,
            crew = 0, turnTime = Instant.EPOCH, meta = mapOf(PersonPolicyState.META_KEY to policy(renown)) + extra)

    private fun county(id: Int = 10, nationId: Int = 1, agriculture: Int = 5_000) = City(
        id = id, name = "縣$id", nationId = nationId, level = 1, population = 50_000, populationMax = 100_000,
        agriculture = agriculture, agricultureMax = 10_000, commerce = 5_000, commerceMax = 10_000,
    )

    private fun world(generals: List<TurnGeneral>, retainers: List<Retainer> = emptyList(), cities: List<City> = listOf(county())) =
        InMemoryTurnWorld(WorldSnapshot(worldId = WorldId(1),
            state = TurnWorldState(1, 200, 2, 3600, Instant.EPOCH, config = mapOf("ruleProfile" to "HWIHA", "mapName" to "han-world-v3")),
            generals = generals, nations = listOf(Nation(1, "N1", "#000"), Nation(2, "N2", "#fff")),
            cities = cities, retainers = retainers,
            generalPositionSnapshot = generals.fold(GeneralPositionSnapshot("r1", HASH,
                cities.map { "p${it.id}" }.toSet(), emptySet())) { snapshot, general ->
                snapshot.withState(GeneralPositionState("r1", HASH, general.id, StrategicNodeRef.LandProvince("p10"), 1))
            },
            cityLandProvinceById = cities.associate { it.id to "p${it.id}" }, administrativeCountyIds = cities.map { it.id }.toSet()))

    private fun tally(vararg entries: RenownEntry) = RenownEvents.withEntries(emptyMap(), entries.toList())
    private fun renown(world: InMemoryTurnWorld, id: Int) = PersonPolicyState.read(world.getGeneralById(id)!!.meta)!!.renownCapacity
    private fun entries(world: InMemoryTurnWorld, id: Int) = RenownEvents.entries(world.getGeneralById(id)!!.meta)
    private fun assign(countyId: Int, nationId: Int = 1) =
        mapOf(CountyAssignment.META_KEY to CountyAssignment("d-$countyId", 1, nationId, countyId).toMetaValue())

    @Test fun `월단평은 지난 달 사건만 적용하고 사유를 종류로만 발표한다`() {
        val meta = tally(
            RenownEntry(RenownEventKind.WAR_MERIT, "0200-01", RenownEventSource.COUNTY_CAPTURE),
            RenownEntry(RenownEventKind.DISPATCH_REFUSAL, "0200-01", RenownEventSource.DISPATCH_REFUSAL),
            RenownEntry(RenownEventKind.DEFEAT, "0200-02", RenownEventSource.COUNTY_LOSS),
        )
        val world = world(listOf(person(1, extra = meta), person(2)))
        val recorder = ChangeRecorder()
        val outcome = assertNotNull(MonthlyAssessment(world, recorder, curve).assess(200, 2))
        assertEquals(30 + 3 - 4, renown(world, 1))
        assertEquals(listOf(RenownEventKind.DEFEAT), entries(world, 1).map { it.kind }, "이번 달 사건은 다음 달로 넘긴다")
        assertEquals(30, renown(world, 2))
        assertEquals(listOf(2, 1), outcome.ranking)

        @Suppress("UNCHECKED_CAST")
        val reasons = world.getState().meta[MonthlyAssessment.REASONS_KEY] as Map<String, Any?>
        assertEquals("0200-02", reasons["stamp"])
        assertEquals(mapOf("1" to listOf(
            mapOf("kind" to "warMerit", "count" to 1, "amount" to 3),
            mapOf("kind" to "dispatchRefusal", "count" to 1, "amount" to -4),
        )), reasons["byGeneral"])
        assertFalse("COUNTY_CAPTURE" in reasons.toString(), "원인은 발표하지 않는다")

        val assessed = world.peekLogs().single { it.eventKind == RecordKind.YUEDAN_ASSESSED }
        assertEquals(1, assessed.generalId); assertEquals("general", assessed.scope)
        assertEquals("월단평: 명망 30 → 29 (전공·발령 거절)", assessed.text)
        val announced = world.peekLogs().single { it.eventKind == RecordKind.YUEDAN_ANNOUNCED }
        assertEquals("global", announced.scope); assertNull(announced.generalId)
        assertEquals(listOf(2, 1), (announced.meta!![RecordKind.REFS_META_KEY] as Map<*, *>)["top"])
        // 같은 달 두 번은 막힌다.
        assertTrue(MonthlyAssessment(world, recorder, curve).assess(200, 2)!!.alreadyStamped)
    }

    @Test fun `이탈 판정은 배신이 아니다 - 명망 사건도 배신 사유도 없고 이탈 기록만 남는다`() {
        // 2026-09-23 사용자 결정 「이탈과 배신은 구분해야지」: 이탈 0, 배신 −8 유지.
        // 주인 명망 10(하한), 휘하 코스트 7+7 → 충성 낮은 3 이 이탈 판정.
        val cards = listOf(Retainer(21, 1, "EXISTING", 2, "G2", "guest", loyalty = 50),
            Retainer(22, 1, "EXISTING", 3, "G3", "guest", loyalty = 20))
        val world = world(listOf(person(1, renown = 10), person(2), person(3)), cards)
        val recorder = ChangeRecorder()
        for ((month, stamp) in listOf(2 to "0200-02", 3 to "0200-03")) {
            world.setCurrentDate(200, month, 1)
            assertEquals(1, MonthlyAssessment(world, recorder, curve).assess(200, month)!!.overCap)
            assertTrue(entries(world, 3).isEmpty(), "$stamp: 이탈 판정은 월단평 사건을 쌓지 않는다")
            assertEquals(30, renown(world, 3), "$stamp: 이탈은 명망 0 이다")
            @Suppress("UNCHECKED_CAST")
            val reasons = world.getState().meta[MonthlyAssessment.REASONS_KEY] as Map<String, Any?>
            assertFalse("betrayal" in reasons.toString(), "$stamp: 월단평 사유에 배신이 없다 — $reasons")
        }
        val logs = world.peekLogs()
        assertEquals(listOf(1, 1), logs.filter { it.eventKind == RecordKind.DEPARTURE_JUDGED }.map { it.generalId })
        val departed = logs.filter { it.eventKind == RecordKind.RETINUE_DEPARTED }
        assertEquals(listOf(3, 3), departed.map { it.generalId }, "판정된 인물 본인 앞 이탈 기록")
        assertEquals(mapOf("stamp" to "0200-02", "masterId" to 1, "retainerId" to 22),
            departed.first().meta!![RecordKind.REFS_META_KEY])
        assertTrue(logs.none { it.eventKind == RecordKind.RENOWN_EVENT }, "명망 사건 알림도 없다")
        assertTrue(world.getGeneralById(3)!!.meta.keys.none { it.startsWith("departure") }, "이탈 표식을 남기지 않는다")
    }

    @Test fun `치적 창은 관할 縣 지표가 문턱 이상 오른 달에만 관할 장수에게 치적을 쌓는다`() {
        val world = world(listOf(person(1), person(2, extra = assign(10)), person(3, extra = assign(11))),
            cities = listOf(county(10), county(11, agriculture = 5_000)))
        val recorder = ChangeRecorder()
        val window = CountyMeritWindow(world, recorder)
        assertEquals(2, window.open(200, 1))
        assertEquals(0, window.open(200, 1), "같은 달은 다시 열지 않는다")
        world.applyCityDirtyFree(world.getCityById(10)!!.copy(agriculture = 5_200))   // 상한의 2%
        world.applyCityDirtyFree(world.getCityById(11)!!.copy(agriculture = 5_199))   // 문턱 아래
        assertEquals(listOf(2), window.close(200, 2))
        assertEquals(listOf(RenownEntry(RenownEventKind.DOMESTIC_MERIT, "0200-01", RenownEventSource.COUNTY_INDICATOR_RISE)),
            entries(world, 2), "창을 연 달의 도장이라 같은 경계의 월단평이 적용한다")
        assertTrue(entries(world, 3).isEmpty())
        assertTrue(window.close(200, 2).isEmpty(), "닫은 창은 다시 닫지 않는다")
        MonthlyAssessment(world, recorder, curve).assess(200, 2)
        assertEquals(30 + curve.domesticMerit, renown(world, 2))
    }

    @Test fun `빼앗긴 縣 은 옛 관할 장수의 치적이 아니다`() {
        val world = world(listOf(person(2, extra = assign(10))))
        val recorder = ChangeRecorder()
        val window = CountyMeritWindow(world, recorder)
        window.open(200, 1)
        world.applyCityDirtyFree(world.getCityById(10)!!.copy(nationId = 2, agriculture = 9_000))
        assertTrue(window.close(200, 2).isEmpty())
    }

    @Test fun `縣 점령 훅은 점령·관할 장수에게 사건을, 두 세력에 공개 기록을 남긴다`() {
        val world = world(listOf(person(2, extra = assign(10)), person(5, nationId = 2)))
        val recorder = ChangeRecorder()
        val events = RenownEventRecorder(world, recorder)
        assertEquals(listOf(2, 5), events.onCountyCaptured(10, previousNationId = 1, captorNationId = 2, capturerIds = listOf(5)))
        assertEquals(RenownEventSource.COUNTY_LOSS, entries(world, 2).single().source)
        assertEquals(RenownEventSource.COUNTY_CAPTURE, entries(world, 5).single().source)
        val nationRecords = world.peekLogs().filter { it.scope == "nation" }
        assertEquals(listOf(2 to RecordKind.COUNTY_CAPTURED, 1 to RecordKind.COUNTY_LOST),
            nationRecords.map { it.nationId to it.eventKind })
        assertTrue(nationRecords.all { it.category == "history" })
        assertEquals("縣10을 점령했습니다.", nationRecords.first().text)
        // 같은 달 두 번째 승리·점령은 사건이 아니다.
        assertTrue(events.onEncounterResolved(listOf(5), emptyList()).isEmpty())
        assertTrue(events.onEncounterResolved(emptyList(), listOf(2)).isEmpty(),
            "패전(조우)은 패전(縣 상실)과 같은 종류라 같은 달엔 한 건이다")
        assertEquals(1, entries(world, 2).size)
        assertEquals(2, world.peekLogs().count { it.eventKind == RecordKind.RENOWN_EVENT }, "새로 쌓인 사건만 본인에게 알린다")
    }

    @Test fun `월세입 기록은 세력 내부 요약으로만 남는다`() {
        val world = world(emptyList(), cities = listOf(county(10).copy(supplyState = 1, meta = mapOf(
            opensamguk.logic.economy.CountyWarehouse.META_KEY to
                opensamguk.logic.economy.CountyWarehouse(10, 0, opensamguk.logic.economy.Resources()).toMetaValue()))))
        MonthlyCountyIncome(world, ChangeRecorder()).credit(200, 2)
        val record = world.peekLogs().single()
        assertEquals(RecordKind.INCOME_MONTHLY, record.eventKind)
        assertEquals("nation", record.scope); assertEquals("summary", record.category); assertEquals(1, record.nationId)
        assertFalse(record.eventKind in RecordKind.NATION_SUMMARY_KINDS)
    }
}
