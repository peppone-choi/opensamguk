package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScenarioCatalogServiceTest {
    @Test
    fun `classpath scenario resources expose code and title`() {
        val scenarios = ScenarioCatalogService(ObjectMapper()).list().scenarios
        val byCode = scenarios.associateBy { it.code }

        assertEquals(30, scenarios.size)
        // OPENSAM-151 — 9000번대(v2 샌드박스)는 v1 선택 목록에 노출되지 않는다. scenario_9200.json 이
        // 클래스패스에 실재하는데도 목록에 없어야 한다(개수만 세면 필터가 죽어도 안 보인다).
        assertTrue(
            ScenarioCatalogServiceTest::class.java.getResource("/scenario/scenario_9200.json") != null,
            "scenario_9200.json 이 클래스패스에 없다 — 이 테스트가 필터를 검증하지 못한다",
        )
        assertTrue(byCode["scenario_9200"] == null, "v2 샌드박스 시나리오가 v1 목록에 노출됐다")
        assertEquals("【공백지】 일반", byCode["scenario_0"]?.title)
        assertEquals("【공백지】 요양 ", byCode["scenario_914"]?.title)
        assertEquals("【역사모드1】 황건적의 난", byCode["scenario_1010"]?.title)
        assertEquals("【역사모드11】 출사표", byCode["scenario_1110"]?.title)
        assertEquals("【IF모드1】 백마장군의 위세", byCode["scenario_1120"]?.title)
        assertEquals(
            scenarios.map { it.code }.sortedBy { it.removePrefix("scenario_").toInt() },
            scenarios.map { it.code },
        )
    }
}
