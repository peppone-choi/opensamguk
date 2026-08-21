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

        val activeCodes = listOf(
            "scenario_1010",
            "scenario_1020",
            "scenario_1021",
            "scenario_1030",
            "scenario_1031",
            "scenario_1040",
            "scenario_1041",
            "scenario_1050",
            "scenario_1060",
            "scenario_1070",
            "scenario_1080",
            "scenario_1090",
            "scenario_1100",
            "scenario_1110",
            "scenario_1120",
        )
        assertEquals(activeCodes, scenarios.map { it.code })

        val retiredCodes = listOf(
            "scenario_0",
            "scenario_1",
            "scenario_2",
            "scenario_900",
            "scenario_901",
            "scenario_902",
            "scenario_903",
            "scenario_905",
            "scenario_906",
            "scenario_908",
            "scenario_910",
            "scenario_911",
            "scenario_912",
            "scenario_913",
            "scenario_914",
            "scenario_9200",
        )
        retiredCodes.forEach { code ->
            assertTrue(
                ScenarioCatalogServiceTest::class.java.getResource("/scenario/$code.json") != null,
                "$code.json 회귀 픽스처가 클래스패스에서 사라졌다",
            )
            assertTrue(byCode[code] == null, "은퇴 시나리오 $code 가 런타임 목록에 노출됐다")
        }
        assertEquals("【역사모드1】 황건적의 난", byCode["scenario_1010"]?.title)
        assertEquals("【역사모드11】 출사표", byCode["scenario_1110"]?.title)
        assertEquals("【IF모드1】 백마장군의 위세", byCode["scenario_1120"]?.title)
        assertEquals(
            scenarios.map { it.code }.sortedBy { it.removePrefix("scenario_").toInt() },
            scenarios.map { it.code },
        )
    }
}
