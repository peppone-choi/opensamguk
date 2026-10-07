package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.dto.ScenarioListResponse
import opensamguk.gateway.dto.ScenarioOption
import opensamguk.infra.seed.ScenarioJson
import opensamguk.logic.input.RuleProfile
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service

@Service
class ScenarioCatalogService(
    private val objectMapper: ObjectMapper,
) {
    private val resolver = PathMatchingResourcePatternResolver()

    private val approval by lazy {
        ClassPathResource("scenario-reset-catalog.json").inputStream.use { input ->
            val catalog = objectMapper.readTree(input)
            require(catalog.path("schemaVersion").asInt() == 1)
            val codes = catalog.path("approvedCodes").map { it.asText() }
            require(codes.isNotEmpty() && codes.distinct().size == codes.size &&
                codes.all { it.matches(Regex("scenario_[0-9]+")) })
            require(catalog.path("defaultCode").asText() in codes)
            catalog.path("defaultCode").asText() to codes.toSet()
        }
    }

    val defaultCode: String get() = approval.first

    private val currentOptions by lazy {
        options(resolver.getResources("classpath*:scenario/scenario_*.json").toList())
    }

    fun list(): ScenarioListResponse = currentOptions

    fun isSelectable(code: String): Boolean = list().scenarios.any { it.code == code }

    /** Approval alone cannot make an absent or retired resource playable. */
    internal fun options(resources: List<Resource>): ScenarioListResponse {
        val scenarios = resources
            .mapNotNull { resource ->
                val filename = resource.filename ?: return@mapNotNull null
                val code = filename.removeSuffix(".json")
                if (filename != "$code.json" || code !in approval.second) return@mapNotNull null
                val text = resource.inputStream.bufferedReader().use { it.readText() }
                try {
                    val scenario = ScenarioJson.loadScenario(text)
                    if (scenario.ruleProfile != RuleProfile.HWIHA || scenario.title.isBlank() ||
                        scenario.seedContract == null || scenario.warehouses == null ||
                        scenario.nations.isEmpty() || scenario.rulers.size != scenario.nations.size ||
                        scenario.generals.none { it.personPolicy != null }) return@mapNotNull null
                    ScenarioOption(code = code, title = scenario.title)
                } catch (_: RuntimeException) {
                    null
                }
            }
            .distinctBy { it.code }
            .sortedBy { it.code.removePrefix("scenario_").toIntOrNull() ?: Int.MAX_VALUE }
        return ScenarioListResponse(scenarios)
    }
}
