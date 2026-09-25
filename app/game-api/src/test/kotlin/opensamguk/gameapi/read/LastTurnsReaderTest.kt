package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.dto.LastTurnsResponse
import opensamguk.gameapi.web.LastTurnsController
import opensamguk.logic.input.RecordKind
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.*
import java.util.Optional
import kotlin.test.*

class LastTurnsReaderTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val records = mock(RecordReadRepository::class.java)
    private val mapper = ObjectMapper()
    private val reader = LastTurnsReader(generals, worlds, records, mapper)
    private val controller = LastTurnsController(reader)

    // 지금: 200년 1월 중순. 12순 창은 199년 9월 상순 … 200년 1월 중순(해를 넘는다).
    private val world = WorldStateReadEntity(id = 1, currentYear = 200, currentMonth = 1, currentPhase = 2,
        config = mapOf("ruleProfile" to "HWIHA"))
    private val me = GeneralReadEntity(id = 1, worldId = 1, name = "조조", nationId = 3, userId = "41")
    private val other = GeneralReadEntity(id = 9, worldId = 1, name = "남", nationId = 4, userId = "42")

    private fun row(id: Long, y: Int, m: Int, p: Int, kind: String, text: String, refs: String? = null) =
        RecordRow(id, y, m, p, kind, text, refs)

    private fun setup(profile: String = "HWIHA") {
        world.config = mapOf("ruleProfile" to profile)
        `when`(worlds.findProcessWorld()).thenReturn(world)
        `when`(generals.findById(1)).thenReturn(Optional.of(me))
        `when`(generals.findById(9)).thenReturn(Optional.of(other))
        `when`(generals.findById(99)).thenReturn(Optional.empty())
    }

    @Test fun `principal 없음 401, 남의 장수·없는 장수 403, 본인 200 no-store`() {
        setup()
        `when`(records.personal(anyInt(), any(), any())).thenReturn(emptyList())
        `when`(records.summary(anyInt(), anyCollection(), anyCollection(), any(), any())).thenReturn(emptyList())
        assertEquals(401, controller.lastTurns(null, 1, 12).statusCode.value())
        assertEquals(401, controller.lastTurns(0, 1, 12).statusCode.value())
        assertEquals(401, controller.lastTurns(Int.MAX_VALUE.toLong() + 1, 1, 12).statusCode.value())
        assertEquals(403, controller.lastTurns(42, 1, 12).statusCode.value())
        assertEquals(403, controller.lastTurns(41, 99, 12).statusCode.value())
        val ok = controller.lastTurns(41, 1, 12)
        assertEquals(200, ok.statusCode.value()); assertEquals("no-store", ok.headers.cacheControl)
    }

    @Test fun `소유 확인이 먼저다 - 남의 장수로는 기록을 읽지 않는다`() {
        setup()
        assertFailsWith<CampForbidden> { reader.lastTurns(9, 41, 12) }
        verifyNoInteractions(records)
    }

    @Test fun `휘하 규칙이 아니면 WRONG_RULE_PROFILE 빈 데이터`() {
        setup(profile = "SAMMO")
        assertEquals(LastTurnsResponse("WRONG_RULE_PROFILE"), reader.lastTurns(1, 41, 12))
        verifyNoInteractions(records)
    }

    @Test fun `12순 창을 최근 순부터, 빈 순도 칸으로, 같은 순은 기록 순으로 싣는다`() {
        setup()
        val from = TurnStamp(199, 9, 3)
        val now = TurnStamp(200, 1, 2)
        `when`(records.personal(1, from, now)).thenReturn(listOf(
            row(5, 199, 12, 3, RecordKind.DISPATCH_RECEIVED, "발령이 도착했습니다.", """{"dispatchId":"d-1","countyId":10}"""),
            row(7, 200, 1, 2, RecordKind.MARCH_ASSIGNMENT, "발령지로 행군하고 있습니다.", """{"stop":"BUDGET_EXHAUSTED"}"""),
            row(8, 200, 1, 2, RecordKind.RENOWN_EVENT, "월단평 사건", "not json"),
        ))
        `when`(records.summary(3, RecordKind.NATION_SUMMARY_KINDS, RecordKind.WORLD_SUMMARY_KINDS, from, now))
            .thenReturn(listOf(
                row(3, 199, 11, 1, RecordKind.COUNTY_CAPTURED, "縣10을 점령했습니다.", """{"countyId":10}"""),
                row(6, 200, 1, 1, RecordKind.YUEDAN_ANNOUNCED, "【월단평】", """{"stamp":"0200-01","top":[1,2]}"""),
            ))
        val out = reader.lastTurns(1, 41, 12)
        assertEquals("READY", out.status)
        assertEquals(12, out.turns.size)
        assertEquals(Triple(200, 1, 2), out.turns.first().let { Triple(it.year, it.month, it.phase) })
        assertEquals(Triple(199, 9, 3), out.turns.last().let { Triple(it.year, it.month, it.phase) })
        assertEquals("중순", out.turns.first().phaseLabel); assertEquals("하순", out.turns.last().phaseLabel)
        assertEquals(listOf(RecordKind.MARCH_ASSIGNMENT, RecordKind.RENOWN_EVENT), out.turns.first().entries.map { it.kind })
        assertEquals(emptyMap(), out.turns.first().entries.last().refs, "읽을 수 없는 식별자는 빈 묶음이다")
        val december = out.turns.single { it.year == 199 && it.month == 12 && it.phase == 3 }
        assertEquals(mapOf("dispatchId" to "d-1", "countyId" to 10), december.entries.single().refs)
        assertEquals(10, out.turns.count { it.entries.isEmpty() }, "12칸 중 기록이 있는 순은 둘")
        assertEquals(listOf(RecordKind.YUEDAN_ANNOUNCED, RecordKind.COUNTY_CAPTURED), out.nationSummary.map { it.kind },
            "요약도 최근 순부터다")
        assertEquals("상순", out.nationSummary.first().phaseLabel)
    }

    @Test fun `limit 은 1–36 으로 자르고 세계 시작 전으로 넘어가지 않는다`() {
        setup()
        `when`(records.personal(anyInt(), any(), any())).thenReturn(emptyList())
        `when`(records.summary(anyInt(), anyCollection(), anyCollection(), any(), any())).thenReturn(emptyList())
        assertEquals(1, reader.lastTurns(1, 41, 0).turns.size)
        assertEquals(36, reader.lastTurns(1, 41, 500).turns.size)
        world.currentYear = 0; world.currentMonth = 1; world.currentPhase = 2
        assertEquals(2, reader.lastTurns(1, 41, 12).turns.size)
    }

    @Test fun `세력 요약은 공개 종류만 묻는다 - 월세입은 요약 종류가 아니다`() {
        assertFalse(RecordKind.INCOME_MONTHLY in RecordKind.NATION_SUMMARY_KINDS)
        assertFalse(RecordKind.INCOME_MONTHLY in RecordKind.WORLD_SUMMARY_KINDS)
        assertEquals(setOf(RecordKind.COUNTY_CAPTURED, RecordKind.COUNTY_LOST), RecordKind.NATION_SUMMARY_KINDS)
        assertEquals(setOf(RecordKind.YUEDAN_ANNOUNCED), RecordKind.WORLD_SUMMARY_KINDS)
    }

    @Test fun `순 서수는 해를 넘어 되돌릴 수 있다`() {
        for (ordinal in listOf(0, 1, 2, 3, 35, 36, 200 * 36 + 1)) assertEquals(ordinal, TurnStamp.ofOrdinal(ordinal).ordinal)
        assertEquals(TurnStamp(199, 12, 3).ordinal + 1, TurnStamp(200, 1, 1).ordinal)
    }

    /** Kotlin 비널 인자에 쓰는 Mockito any(). */
    private fun <T> any(): T {
        org.mockito.ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return TurnStamp(0, 1, 1) as T
    }
}
