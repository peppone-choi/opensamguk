package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.dto.ScenarioListResponse
import opensamguk.gateway.dto.ScenarioOption
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service

@Service
class ScenarioCatalogService(
    private val objectMapper: ObjectMapper,
) {
    private val resolver = PathMatchingResourcePatternResolver()

    fun list(): ScenarioListResponse {
        val scenarios = resolver.getResources("classpath*:scenario/scenario_*.json")
            .mapNotNull { resource ->
                val filename = resource.filename ?: return@mapNotNull null
                val code = filename.removeSuffix(".json")
                if (code !in ACTIVE_PRODUCT_SCENARIO_CODES) return@mapNotNull null
                resource.inputStream.use { input ->
                    val title = objectMapper.readTree(input).path("title").asText(code)
                    ScenarioOption(code = code, title = title)
                }
            }
            .sortedBy { it.code.removePrefix("scenario_").toIntOrNull() ?: Int.MAX_VALUE }
        return ScenarioListResponse(scenarios)
    }

    companion object {
        private val ACTIVE_PRODUCT_SCENARIO_CODES = setOf(
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
    }
}
