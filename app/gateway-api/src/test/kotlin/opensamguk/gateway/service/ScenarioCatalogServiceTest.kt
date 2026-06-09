package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScenarioCatalogServiceTest {
    @Test
    fun `classpath scenario resources expose code and title`() {
        val scenarios = ScenarioCatalogService(ObjectMapper()).list().scenarios

        assertTrue(scenarios.any { it.code == "scenario_1010" && it.title == "【역사모드1】 황건적의 난" })
        assertTrue(scenarios.any { it.code == "scenario_1030" && it.title == "【역사모드3】 군웅할거" })
        assertEquals(scenarios.map { it.code }.sorted(), scenarios.map { it.code })
    }
}
