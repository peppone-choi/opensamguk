package opensamguk.infra.seed

import java.nio.file.Files
import java.nio.file.Path

class EffectiveScenarioResolver(
    private val scenarioDir: String = "",
    private val classLoader: ClassLoader = EffectiveScenarioResolver::class.java.classLoader,
    private val onSelectedOriginal: ((CapturedScenarioOriginal) -> Unit)? = null,
) {

    fun resolve(scenarioCode: String): Scenario =
        ScenarioJson.loadScenario(readScenarioJson(scenarioCode))

    fun readScenarioJson(scenarioCode: String): String = readScenarioOriginal(scenarioCode).utf8()

    /** One original object feeds the observer and the same UTF8 parser. The
     * observer is a source seam, never a receipt/approval installer. */
    fun readScenarioOriginal(scenarioCode: String): CapturedScenarioOriginal {
        externalScenarioPath(scenarioCode)?.let { path ->
            RepositoryInputTrace.file(path)
            return CapturedScenarioOriginal.external(path, "$scenarioCode.json").also { onSelectedOriginal?.invoke(it) }
        }

        val resource = "scenario/$scenarioCode.json"
        RepositoryInputTrace.resource(resource)
        val original = try {
            CapturedScenarioOriginal.classpath(classLoader, resource)
        } catch (_: SelectedSourceUnavailable) {
            throw EffectiveScenarioNotFoundException(scenarioCode)
        }
        onSelectedOriginal?.invoke(original)
        return original
    }

    private fun externalScenarioPath(scenarioCode: String): Path? {
        if (scenarioDir.isBlank()) return null
        val path = Path.of(scenarioDir).resolve("$scenarioCode.json")
        return if (Files.notExists(path)) null else path
    }
}

class EffectiveScenarioNotFoundException(scenarioCode: String) :
    IllegalStateException("Effective scenario '$scenarioCode' is unavailable from the configured directory or classpath")
