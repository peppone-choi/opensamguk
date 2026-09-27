package opensamguk.logic.input

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AptitudeTest {
    private fun stats(l: Int = 0, s: Int = 0, i: Int = 0, p: Int = 0, c: Int = 0) = Aptitude.Stats(l, s, i, p, c)

    @Test
    fun `classpath 가중값은 저장소 정본 파일과 같다`() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("data/curated/han/aptitude-weights-v1.json")) }
        val file = Aptitude.parse(Files.readString(root.resolve("data/curated/han/aptitude-weights-v1.json")))
        assertEquals(file.denominator, Aptitude.CANON.denominator)
        assertEquals(file.axes, Aptitude.CANON.axes)
    }

    @Test
    fun `승인된 가중식 - 장 6·4 리 7·3 사 8·2 사자 6·4`() {
        val w = Aptitude.CANON.axes
        assertEquals(mapOf(Aptitude.Stat.LEADERSHIP to 6, Aptitude.Stat.STRENGTH to 4), w[Aptitude.Axis.COMMAND])
        assertEquals(mapOf(Aptitude.Stat.POLITICS to 7, Aptitude.Stat.INTELLIGENCE to 3), w[Aptitude.Axis.ADMINISTRATION])
        assertEquals(mapOf(Aptitude.Stat.INTELLIGENCE to 8, Aptitude.Stat.POLITICS to 2), w[Aptitude.Axis.STRATEGY])
        assertEquals(mapOf(Aptitude.Stat.CHARM to 6, Aptitude.Stat.POLITICS to 4), w[Aptitude.Axis.ENVOY])
    }

    @Test
    fun `네 축을 가중 평균으로 계산한다`() {
        // 조조 같은 고르게 높은 인물: 통 100 무 80 지 95 정 90 매 95
        val out = Aptitude.compute(stats(l = 100, s = 80, i = 95, p = 90, c = 95))
        assertEquals(92, out.command)          // 60 + 32
        assertEquals(92, out.administration)   // 63 + 28.5 = 91.5 → 92
        assertEquals(94, out.strategy)         // 76 + 18
        assertEquals(93, out.envoy)            // 57 + 36
    }

    @Test
    fun `0_5 는 올린다 - 은행가 반올림이 아니다`() {
        // 리 = 정치 0.7 + 지력 0.3. 정 0 지 5 → 1.5 → 2, 정 1 지 6 → 2.5 → 3(짝수 반올림이면 2).
        assertEquals(2, Aptitude.compute(stats(i = 5)).administration)
        assertEquals(3, Aptitude.compute(stats(i = 6, p = 1)).administration)
        assertEquals(1, Aptitude.compute(stats(p = 2)).envoy) // 0.8 → 1
        assertEquals(1, Aptitude.compute(stats(p = 1, c = 1)).envoy) // 1.0
        assertEquals(0, Aptitude.compute(stats(i = 1)).administration) // 0.3 → 0
    }

    @Test
    fun `축 합이 분모와 다르거나 모르는 능력치면 거부한다`() {
        val bad = """{"schemaVersion":1,"ledgerId":"aptitude-weights-v1","denominator":10,"axes":{
            "command":{"leadership":6,"strength":3},"administration":{"politics":7,"intelligence":3},
            "strategy":{"intelligence":8,"politics":2},"envoy":{"charm":6,"politics":4}}}"""
        assertFailsWith<IllegalArgumentException> { Aptitude.parse(bad) }
        val unknown = bad.replace("\"strength\":3", "\"luck\":4")
        assertFailsWith<IllegalArgumentException> { Aptitude.parse(unknown) }
        val missingAxis = """{"schemaVersion":1,"ledgerId":"aptitude-weights-v1","denominator":10,"axes":{
            "command":{"leadership":6,"strength":4}}}"""
        assertFailsWith<IllegalArgumentException> { Aptitude.parse(missingAxis) }
    }

    @Test
    fun `음수 능력치는 받지 않는다`() {
        assertFailsWith<IllegalArgumentException> { stats(l = -1) }
    }
}
