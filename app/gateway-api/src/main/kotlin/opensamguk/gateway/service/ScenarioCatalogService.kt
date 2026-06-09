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
                resource.inputStream.use { input ->
                    val title = objectMapper.readTree(input).path("title").asText(code)
                    ScenarioOption(code = code, title = title)
                }
            }
            .sortedBy { it.code.removePrefix("scenario_").toIntOrNull() ?: Int.MAX_VALUE }
        return ScenarioListResponse(scenarios)
    }
}
