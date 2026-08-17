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
                val number = code.removePrefix("scenario_").toIntOrNull()
                if (number != null && number >= V2_SANDBOX_CODE_FLOOR) return@mapNotNull null
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
         * OPENSAM-151 — 9000번대부터는 **v2 샌드박스 전용 시나리오**이고 v1 프로덕션 선택 목록에
         * 노출하지 않는다.
         *
         * `scenario_9200.json`(v2 도시 경제 시험장)은 `V2ProcessCityIncome` 같은 v2 leaf를 부르는데,
         * 그 leaf는 v2 게이트(`V2_ENABLED`) 밖에서는 원장 스토어가 없어 **일부러 죽는다**. 목록에
         * 남겨 두면 v1 운영자가 고를 수 있는 자리에 절대 못 돌리는 월드가 보이게 된다.
         *
         * 기존 대역은 0~2 / 900번대 / 1010~1120이라 겹치지 않는다.
         */
        const val V2_SANDBOX_CODE_FLOOR = 9000
    }
}
