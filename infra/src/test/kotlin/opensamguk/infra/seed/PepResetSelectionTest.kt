package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.logic.world.WorldMapVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PepResetSelectionTest {
    @Test
    fun `selected prepared scenario passes the actual fresh importer before image promotion`() {
        var root = Path.of("").toAbsolutePath()
        while (!Files.isRegularFile(root.resolve("settings.gradle.kts"))) {
            root = requireNotNull(root.parent) { "repository root unavailable" }
        }
        // Read the sole approval source as test input; infra has no gateway dependency.
        val catalog = Files.newBufferedReader(root.resolve(
            "app/gateway-api/src/main/resources/scenario-reset-catalog.json",
        )).use { ObjectMapper().readTree(it) }
        require(catalog.path("schemaVersion").isIntegralNumber && catalog.path("schemaVersion").asInt() == 1)
        val approvedNodes = catalog.path("approvedCodes")
        require(approvedNodes.isArray && approvedNodes.all { it.isTextual })
        val approved = approvedNodes.map { it.asText() }
        require(approved.isNotEmpty() && approved.distinct().size == approved.size &&
            approved.all { it.matches(Regex("scenario_[0-9]+")) })
        val defaultCode = catalog.path("defaultCode")
        require(defaultCode.isTextual && defaultCode.asText() in approved)

        val selected = System.getenv("PEP_SCENARIO_CODE")?.takeIf { it.isNotBlank() }
        val codes = if (selected == null) {
            // Absent historical resources are not prepared; future packaged approvals join automatically.
            approved.filter { javaClass.getResource("/scenario/$it.json") != null }
        } else {
            require(selected in approved) { "unapproved selection $selected" }
            listOf(selected)
        }
        assertTrue(codes.isNotEmpty(), "no prepared scenario selections")
        if (selected == null) assertTrue(defaultCode.asText() in codes, "default scenario unavailable")
        val cities = ScenarioJson.loadMapCities(requireNotNull(javaClass.getResourceAsStream("/map/han-world-v3.json"))
            .bufferedReader().use { it.readText() })
        var captures = 0
        for (code in codes) {
            val scenario = ScenarioJson.loadScenario(requireNotNull(javaClass.getResourceAsStream("/scenario/$code.json")) {
                "unprepared selection $code"
            }.bufferedReader().use { it.readText() })
            for (extended in listOf(false, true)) {
                // Keep only the identity after each call; the next import must not retain the previous graph.
                assertEquals(WorldMapVariant.PROVINCE_WORLD, ScenarioImporter(
                    scenario, cities, scenarioCode = code, scenarioNumber = code.removePrefix("scenario_").toInt(),
                    turnTerm = 60, maxGeneral = 50, firstTurnImmediate = true,
                    extendedGeneral = extended, blockGeneralCreate = 1, artifactsRoot = root,
                    onFreshWorldArtifacts = {},
                ).captureFreshSelectionReadOnly().variant)
                captures++
            }
        }
        assertEquals(codes.size * 2, captures)
        println("PEP_RESET_SELECTION codes=${codes.joinToString(",")} actualCaptures=$captures")
    }
}
