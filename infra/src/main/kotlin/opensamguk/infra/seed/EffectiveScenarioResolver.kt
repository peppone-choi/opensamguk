package opensamguk.infra.seed

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class EffectiveScenarioResolver(
    private val scenarioDir: String = "",
    private val classLoader: ClassLoader = EffectiveScenarioResolver::class.java.classLoader,
) {

    fun resolve(scenarioCode: String): Scenario =
        ScenarioJson.loadScenario(readScenarioJson(scenarioCode))

    fun readScenarioJson(scenarioCode: String): String {
        externalScenarioPath(scenarioCode)?.let { path ->
            return Files.readString(path, StandardCharsets.UTF_8)
        }

        val resource = "scenario/$scenarioCode.json"
        return classLoader.getResourceAsStream(resource)?.use { stream ->
            stream.readBytes().toString(StandardCharsets.UTF_8)
        } ?: throw EffectiveScenarioNotFoundException(scenarioCode)
    }

    private fun externalScenarioPath(scenarioCode: String): Path? {
        if (scenarioDir.isBlank()) return null
        val path = Path.of(scenarioDir).resolve("$scenarioCode.json")
        return if (Files.notExists(path)) null else path
    }
}

class EffectiveScenarioNotFoundException(scenarioCode: String) :
    IllegalStateException("Effective scenario '$scenarioCode' is unavailable from the configured directory or classpath")
