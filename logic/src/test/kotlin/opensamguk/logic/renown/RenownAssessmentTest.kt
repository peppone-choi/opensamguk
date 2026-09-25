package opensamguk.logic.input

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HwihaRenownAssessmentTest {
    private val curve = HwihaRenownAssessment.CANON

    @Test
    fun `오르는 경로와 떨어지는 경로를 함께 누적한다`() {
        val tally = HwihaRenownAssessment.Tally(warMerit = 2, office = 1, defeat = 1)
        // 30 + (3*2) + 4 - 3 = 37
        assertEquals(37, HwihaRenownAssessment.updatedRenown(30, tally, curve))
    }

    @Test
    fun `사건이 없으면 명망을 보존한다 - 월단평은 초기화하지 않는다`() {
        val kept = HwihaRenownAssessment.updatedRenown(83, HwihaRenownAssessment.Tally(), curve)
        assertEquals(83, kept, "설계 §2.8: 월단평마다 30 으로 초기화하지 않는다")
    }

    @Test
    fun `상한과 하한으로 자른다`() {
        val high = HwihaRenownAssessment.updatedRenown(199, HwihaRenownAssessment.Tally(office = 50), curve)
        assertEquals(200, high)
        val low = HwihaRenownAssessment.updatedRenown(12, HwihaRenownAssessment.Tally(betrayal = 50), curve)
        assertEquals(10, low, "하한은 최소 휘하를 남긴다")
    }

    @Test
    fun `코스트 합이 갱신된 명망을 넘으면 충성 낮은 쪽부터 이탈한다`() {
        val retinue = listOf(
            HwihaRenownAssessment.RetainerCard(retainerId = 1, cost = 7, loyalty = 90),
            HwihaRenownAssessment.RetainerCard(retainerId = 2, cost = 7, loyalty = 20),
            HwihaRenownAssessment.RetainerCard(retainerId = 3, cost = 7, loyalty = 50),
        )
        // 배신으로 30 → 22. 코스트 합 21 ≤ 22 이므로 아무도 안 나간다.
        val kept = HwihaRenownAssessment.assess(9, 30, HwihaRenownAssessment.Tally(betrayal = 1), curve, retinue)
        assertEquals(22, kept.renown)
        assertEquals(emptyList(), kept.released)
        assertEquals(21, kept.retainedCost)

        // 배신 둘이면 30 → 14. 21 > 14 이므로 충성 20 이 먼저 나가고 14 ≤ 14 에서 멈춘다.
        val shed = HwihaRenownAssessment.assess(9, 30, HwihaRenownAssessment.Tally(betrayal = 2), curve, retinue)
        assertEquals(14, shed.renown)
        assertEquals(listOf(2), shed.released, "충성이 가장 낮은 휘하가 먼저 이탈한다")
        assertEquals(14, shed.retainedCost)
    }

    @Test
    fun `동점 충성은 id 큰 쪽이 먼저 나간다 - 리플레이가 갈라지지 않게`() {
        val retinue = listOf(
            HwihaRenownAssessment.RetainerCard(1, cost = 10, loyalty = 40),
            HwihaRenownAssessment.RetainerCard(2, cost = 10, loyalty = 40),
        )
        val out = HwihaRenownAssessment.assess(1, 12, HwihaRenownAssessment.Tally(), curve, retinue)
        assertEquals(listOf(2), out.released)
    }

    @Test
    fun `이탈 순번은 assess 와 같은 순서이고 상한 이하면 비어 있다`() {
        val retinue = listOf(
            HwihaRenownAssessment.RetainerCard(1, cost = 5, loyalty = 30),
            HwihaRenownAssessment.RetainerCard(2, cost = 5, loyalty = 10),
            HwihaRenownAssessment.RetainerCard(3, cost = 5, loyalty = 30),
            HwihaRenownAssessment.RetainerCard(4, cost = 5, loyalty = 90),
        )
        // 합 20, 상한 8 → 충성 10(2), 30 동점은 id 큰 3 먼저, 그다음 1. 5 ≤ 8 에서 멈춘다.
        assertEquals(listOf(2, 3, 1), HwihaRenownAssessment.departures(8, retinue))
        assertEquals(
            HwihaRenownAssessment.assess(9, 12, HwihaRenownAssessment.Tally(betrayal = 1), curve, retinue).released,
            HwihaRenownAssessment.departures(10, retinue),
            "월단평(명망 12 → 하한 10)과 조회가 같은 함수를 쓴다",
        )
        assertEquals(emptyList(), HwihaRenownAssessment.departures(20, retinue))
    }

    @Test
    fun `delta 는 자른 뒤 실제 증감을 돌려준다`() {
        val out = HwihaRenownAssessment.assess(
            1, 199, HwihaRenownAssessment.Tally(office = 50), curve, emptyList(),
        )
        assertEquals(1, out.delta, "상한에 막혀 실제로는 1 만 올랐다")
    }

    @Test
    fun `순위는 명망 내림차순 동점은 id 오름차순이다`() {
        val ranked = HwihaRenownAssessment.ranking(mapOf(7 to 50, 3 to 90, 5 to 50, 1 to 90))
        assertEquals(listOf(1, 3, 5, 7), ranked)
    }

    @Test
    fun `잘못된 곡선과 집계는 거절한다`() {
        assertFailsWith<IllegalArgumentException> { curve.copy(floor = 0) }
        assertFailsWith<IllegalArgumentException> { curve.copy(floor = 50, ceiling = 20) }
        assertFailsWith<IllegalArgumentException> { curve.copy(warMerit = -1) }
        assertFailsWith<IllegalArgumentException> { curve.copy(betrayal = 1) }
        assertFailsWith<IllegalArgumentException> { HwihaRenownAssessment.Tally(warMerit = -1) }
        assertFailsWith<IllegalArgumentException> {
            HwihaRenownAssessment.assess(
                1, 30, HwihaRenownAssessment.Tally(), curve,
                listOf(
                    HwihaRenownAssessment.RetainerCard(1, 1, 1),
                    HwihaRenownAssessment.RetainerCard(1, 1, 2),
                ),
            )
        }
    }

    @Test
    fun `정본 데이터 파일이 유효한 곡선으로 읽힌다`() {
        // 코드와 데이터가 갈라지지 않게 하는 두 번째 축: 파일이 그대로 Curve 가 되어야 한다.
        val file = repoRoot().resolve("data/curated/han/hwiha-renown-assessment-v1.json")
        assertTrue(Files.isRegularFile(file), "정본 파일이 있다: $file")
        val text = Files.readString(file)
        fun intOf(key: String): Int {
            val m = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(text)
            return requireNotNull(m) { "$key 가 정본 파일에 없다" }.groupValues[1].toInt()
        }
        val fromFile = HwihaRenownAssessment.Curve(
            warMerit = intOf("warMerit"), domesticMerit = intOf("domesticMerit"),
            office = intOf("office"), bondEvent = intOf("bondEvent"),
            defeat = intOf("defeat"), betrayal = intOf("betrayal"),
            misrule = intOf("misrule"), dispatchRefusal = intOf("dispatchRefusal"),
            floor = intOf("floor"), ceiling = intOf("ceiling"),
        )
        assertEquals(
            HwihaRenownAssessment.CANON, fromFile,
            "코드의 CANON 과 정본 파일이 갈라졌다 — 한쪽만 고치면 런타임이 파일을 배신한다",
        )
        assertEquals(HwihaRenownRules.INITIAL_CAPACITY, intOf("initialRenown"), "초기 명망은 한 곳에서만 온다")
        assertTrue(fromFile.ceiling > HwihaRenownRules.INITIAL_CAPACITY, "상한이 초기값보다 높다")
        assertTrue(fromFile.floor <= HwihaRenownRules.INITIAL_CAPACITY, "하한이 초기값보다 낮거나 같다")
    }

    private fun repoRoot(): Path {
        var at: Path? = Path.of("").toAbsolutePath()
        while (at != null && !Files.isDirectory(at.resolve("data/curated"))) at = at.parent
        return requireNotNull(at) { "data/curated 를 가진 저장소 루트를 찾지 못했다" }
    }
}
