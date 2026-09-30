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
        /**
         * 관리자 리셋에 고를 수 있는 시나리오. 세계 형식(worldFormat)을 선언하고 importer 가 받는 것만 둔다.
         * 옛 삼모 역사·IF 시나리오(1010–1120)는 ruleProfile·휘하 선언이 없어 `ScenarioImporter` 가 거절하므로
         * 목록에서 뺐다(#917). 190 역사 시나리오(3190)는 F1 시드가 main 에 들어온 뒤 따로 더한다.
         */
        private val ACTIVE_PRODUCT_SCENARIO_CODES = setOf(
            "scenario_990002",
        )
    }
}
