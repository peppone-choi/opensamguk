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
