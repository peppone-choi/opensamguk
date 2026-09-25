package opensamguk.logic.renown

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import opensamguk.logic.input.HwihaCountyAssignment

class RenownEventsTest {
    private val base: Map<String, Any?> = mapOf("keep" to "preserved")

    @Test
    fun `같은 달 같은 종류는 한 번만 쌓이고 먼저 온 원인이 남는다`() {
        val first = RenownEvents.recordRenownEvent(base, RenownEventSource.COUNTY_CAPTURE, "0200-03")
        assertTrue(first.recorded)
        // 같은 달 전공(다른 원인) — 무동작, 같은 meta 객체를 돌려준다.
        val second = RenownEvents.recordRenownEvent(first.meta, RenownEventSource.ENCOUNTER_VICTORY, "0200-03")
        assertFalse(second.recorded)
        assertSame(first.meta, second.meta)
        assertEquals(
            listOf(RenownEntry(RenownEventKind.WAR_MERIT, "0200-03", RenownEventSource.COUNTY_CAPTURE)),
            RenownEvents.entries(second.meta),
        )
        assertEquals("preserved", second.meta["keep"])
    }

    @Test
    fun `다른 종류나 다른 달은 따로 쌓인다`() {
        var meta = base
        meta = RenownEvents.recordRenownEvent(meta, RenownEventSource.COUNTY_CAPTURE, "0200-03").meta
        meta = RenownEvents.recordRenownEvent(meta, RenownEventSource.ENCOUNTER_DEFEAT, "0200-03").meta
        meta = RenownEvents.recordRenownEvent(meta, RenownEventSource.ENCOUNTER_VICTORY, "0200-04").meta
        val tally = RenownEvents.tallyOf(RenownEvents.entries(meta))
        assertEquals(RenownAssessment.Tally(warMerit = 2, defeat = 1), tally)
    }

    @Test
    fun `월단평은 이번 달 이전 사건만 적용하고 이번 달 사건은 남긴다`() {
        var meta = base
        meta = RenownEvents.recordRenownEvent(meta, RenownEventSource.DISPATCH_REFUSAL, "0199-12").meta
        meta = RenownEvents.recordRenownEvent(meta, RenownEventSource.DEFECTION, "0200-01").meta
        val split = RenownEvents.split(meta, "0200-01")
        assertEquals(listOf(RenownEventKind.DISPATCH_REFUSAL), split.applied.map { it.kind })
        assertEquals(listOf(RenownEventKind.BETRAYAL), split.remaining.map { it.kind })
        // 해가 바뀌어도 순서가 맞다(문자열이 아니라 월 서수로 비교).
        assertTrue(RenownEvents.monthOrdinal("0199-12") < RenownEvents.monthOrdinal("0200-01"))
    }

    @Test
    fun `빈 집계는 키를 지우고 읽을 수 없는 줄은 건너뛴다`() {
        assertEquals(base, RenownEvents.withEntries(base + (RenownEvents.META_KEY to mapOf("entries" to emptyList<Any>())), emptyList()))
        val messy = base + (RenownEvents.META_KEY to mapOf("entries" to listOf(
            mapOf("kind" to "warMerit", "stamp" to "0200-01"),
            mapOf("kind" to "nope", "stamp" to "0200-01"),
            mapOf("kind" to "defeat", "stamp" to "0200-13"),
            mapOf("kind" to "defeat", "stamp" to "0200-02", "source" to "COUNTY_CAPTURE"),
            "garbage",
        )))
        assertEquals(listOf(RenownEventKind.WAR_MERIT), RenownEvents.entries(messy).map { it.kind })
        // 옛 개수 형식(작성자가 없던 시절)은 집계로 읽지 않는다.
        assertTrue(RenownEvents.entries(mapOf(RenownEvents.META_KEY to mapOf("warMerit" to 2))).isEmpty())
    }

    @Test
    fun `원인은 제 종류에만 붙고 도장 형식을 검사한다`() {
        assertFailsWith<IllegalArgumentException> {
            RenownEvents.recordRenownEvent(base, RenownEventKind.DEFEAT, "0200-01", RenownEventSource.COUNTY_CAPTURE)
        }
        assertFailsWith<IllegalArgumentException> { RenownEvents.recordRenownEvent(base, RenownEventKind.DEFEAT, "200-1") }
        assertEquals("0200-01", RenownEvents.stampOf(200, 1))
    }

    @Test
    fun `조우 훅은 승자에게 전공, 패자에게 패전을 id 순으로 돌려준다`() {
        val metas = mapOf(3 to base, 1 to base, 2 to base)
        val updates = RenownHooks.onEncounterResolved(listOf(3, 1), listOf(2), 200, 5) { metas[it] }
        assertEquals(listOf(1, 2, 3), updates.map { it.generalId })
        assertEquals(
            listOf(RenownEventSource.ENCOUNTER_VICTORY, RenownEventSource.ENCOUNTER_DEFEAT, RenownEventSource.ENCOUNTER_VICTORY),
            updates.map { it.entry.source },
        )
        // 같은 달 두 번째 승리는 결과에 없다.
        val again = RenownHooks.onEncounterResolved(listOf(1), emptyList(), 200, 5) { updates.first().meta }
        assertTrue(again.isEmpty())
        // 없는 장수는 건너뛴다. 한 장수가 양쪽이면 거절한다.
        assertTrue(RenownHooks.onEncounterResolved(listOf(9), emptyList(), 200, 5) { null }.isEmpty())
        assertFailsWith<IllegalArgumentException> { RenownHooks.onEncounterResolved(listOf(1), listOf(1), 200, 5) { base } }
    }

    @Test
    fun `縣 점령 훅과 관할 장수`() {
        val assigned = base + (HwihaCountyAssignment.META_KEY to HwihaCountyAssignment("d-1", 9, 2, 10).toMetaValue())
        val otherNation = base + (HwihaCountyAssignment.META_KEY to HwihaCountyAssignment("d-2", 8, 3, 10).toMetaValue())
        val broken = base + (HwihaCountyAssignment.META_KEY to "broken")
        val people = mapOf(5 to assigned, 4 to otherNation, 6 to broken, 7 to base)
        assertEquals(listOf(5), RenownHooks.countyHolderIds(10, 2, people))
        val updates = RenownHooks.onCountyCaptured(listOf(1), listOf(5), 200, 5) { if (it == 1) base else people[it] }
        assertEquals(mapOf(1 to RenownEventSource.COUNTY_CAPTURE, 5 to RenownEventSource.COUNTY_LOSS),
            updates.associate { it.generalId to it.entry.source })
    }

    @Test
    fun `치적은 상한의 문턱 이상 오른 지표가 있을 때만이다`() {
        val max = DomesticMerit.Indicators(100_000, 10_000, 10_000)
        val open = DomesticMerit.Indicators(50_000, 5_000, 5_000)
        // 전답 +200 = 상한의 2% → 치적.
        assertTrue(DomesticMerit.risen(open, open.copy(agriculture = 5_200), max))
        // +199 는 문턱 아래.
        assertFalse(DomesticMerit.risen(open, open.copy(agriculture = 5_199), max))
        // 내려간 지표·상한 0 은 보지 않는다.
        assertFalse(DomesticMerit.risen(open, open.copy(commerce = 4_000), max))
        assertFalse(DomesticMerit.risen(open, open.copy(commerce = 6_000), max.copy(commerce = 0)))
    }

    @Test
    fun `이탈은 배신의 원인이 아니다 - 배신은 실제 배반뿐이다`() {
        // 2026-09-23 사용자 결정 「이탈과 배신은 구분해야지」.
        assertEquals(listOf(RenownEventSource.DEFECTION),
            RenownEventSource.entries.filter { it.kind == RenownEventKind.BETRAYAL })
        assertTrue(RenownEventSource.entries.none { it.name == "DEPARTURE" || "이탈" in it.label })
        assertEquals(-8, RenownEventKind.BETRAYAL.amountIn(RenownAssessment.CANON), "배신 −8 은 그대로다")
    }

    @Test
    fun `정본 데이터 파일과 코드의 임계값·원인 표가 같다`() {
        val text = Files.readString(repoRoot().resolve("data/curated/han/hwiha-renown-events-v1.json"))
        val bp = Regex("\"minRiseBasisPoints\"\\s*:\\s*(\\d+)").find(text)!!.groupValues[1].toInt()
        assertEquals(DomesticMerit.MIN_RISE_BASIS_POINTS, bp)
        assertTrue("\"status\": \"CONFIRMED\"" in text, "2026-09-23 확정 상태를 유지해야 한다")
        for (kind in RenownEventKind.entries) {
            val listed = Regex("\"${kind.key}\"\\s*:\\s*\\[([^]]*)]").find(text)
                ?.groupValues?.get(1)?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }?.toSet()
            val code = RenownEventSource.entries.filter { it.kind == kind }.map { it.name }.toSet()
            assertEquals(code, listed, "원인 표가 갈라졌다: ${kind.key}")
        }
    }

    private fun repoRoot(): Path {
        var at: Path? = Path.of("").toAbsolutePath()
        while (at != null && !Files.isDirectory(at.resolve("data/curated"))) at = at.parent
        return requireNotNull(at) { "data/curated 를 가진 저장소 루트를 찾지 못했다" }
    }
}
