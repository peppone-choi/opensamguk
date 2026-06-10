package opensamguk.engine.config

import opensamguk.logic.tick.CheckStatisticCalculator
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 프로드 턴동결 회귀 테스트 — checkStatistic의 `aux: Map<String, Any?>`는 이종(heterogeneous)
 * 중첩 맵이라 kotlinx `Json.encodeToString`이 런타임에 "Serializer for class 'Any' is not found"를
 * 던진다(2026-06-09 s1/spep 양 서버 181.1 동결 원인 — 연경계(새 달 == 1월) 훅).
 * statistic INSERT 컬럼 매핑은 반드시 MetaJson(PHP `Json::encode` byte-faithful) 경로로
 * 인코딩해야 한다.
 *
 * 주의: 본 테스트의 기대 json 문자열은 MetaJson 계약에서 유도한 것이며 PHP 골든 캡처가
 * 아니다 — statistic 골든(백로그)이 닫힐 때 aux 구조 자체(nations.all dict-vs-array 등)가
 * 재정렬될 수 있다. 패러티 증거로 인용하지 말 것.
 */
class StatisticInsertColumnsTest {

    private fun row(aux: Map<String, Any?>) = CheckStatisticCalculator.StatisticRow(
        year = 181,
        month = 1,
        nationCount = 2,
        nationName = "위, 촉",
        nationHist = "위(10), 촉(8)",
        genCount = "18 (NPC 156)",
        personalHist = "안전(3)",
        specialHist = "농업(2) // 귀병(1)",
        powerHist = "위(1234)",
        crewtype = "보병(5)",
        etc = "etc",
        aux = aux,
    )

    @Test
    fun `이종 중첩 aux 맵을 PHP-compact json으로 인코딩한다 - 턴동결 회귀`() {
        // 실제 프로드 크래시를 일으킨 형태: Int/String/중첩 Map/List 혼합.
        val aux = linkedMapOf<String, Any?>(
            "generals" to linkedMapOf<String, Any?>(
                "avg" to linkedMapOf<String, Any?>("avggold" to 1000, "avgdex" to 12.5),
                "hists" to linkedMapOf<String, Any?>("personal" to linkedMapOf("안전" to 3), "userCnt" to 18),
            ),
            "nations" to linkedMapOf<String, Any?>(
                "all" to listOf(
                    linkedMapOf<String, Any?>("nation" to 1, "name" to "위", "power" to 1234),
                ),
            ),
        )

        val columns = StatisticInsertColumns.from(row(aux))

        assertEquals(
            """{"generals":{"avg":{"avggold":1000,"avgdex":12.5},"hists":{"personal":{"안전":3},"userCnt":18}},"nations":{"all":[{"nation":1,"name":"위","power":1234}]}}""",
            columns["aux"],
        )
    }

    @Test
    fun `statistic 컬럼 키를 테이블 계약 순서로 유지한다`() {
        val columns = StatisticInsertColumns.from(row(emptyMap()))

        assertEquals(
            listOf(
                "year", "month", "nation_count", "nation_name", "nation_hist", "gen_count",
                "personal_hist", "special_hist", "power_hist", "crewtype", "etc", "aux",
            ),
            columns.keys.toList(),
        )
        assertEquals(181, columns["year"])
        assertEquals("{}", columns["aux"])
    }
}
