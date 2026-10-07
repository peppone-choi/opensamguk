package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import opensamguk.infra.seed.ScenarioJson
import org.springframework.core.io.ByteArrayResource

class ScenarioCatalogServiceTest {
    private val mapper = ObjectMapper()
    private val catalog = ScenarioCatalogService(mapper)
    private fun preparedJson(): String = requireNotNull(javaClass.getResourceAsStream("/scenario/scenario_990002.json"))
        .bufferedReader().use { it.readText() }
    private fun resource(code: String, json: String = preparedJson()) = object : ByteArrayResource(json.toByteArray()) {
        override fun getFilename() = "$code.json"
    }
    @Test
    fun `classpath scenario resources expose code and title`() {
        val scenarios = ScenarioCatalogService(ObjectMapper()).list().scenarios
        val byCode = scenarios.associateBy { it.code }

        // importer 가 받는 세계 형식 선언 시나리오만 고를 수 있다(#917).
        assertTrue(scenarios.map { it.code }.containsAll(listOf("scenario_3190", "scenario_990002")))
        assertEquals("동탁의 전횡과 반동탁연합", byCode["scenario_3190"]?.title)

        val retiredCodes = listOf(
            "scenario_0",
            "scenario_1",
            "scenario_2",
            "scenario_900",
            "scenario_901",
            "scenario_902",
            "scenario_903",
            "scenario_905",
            "scenario_906",
            "scenario_908",
            "scenario_910",
            "scenario_911",
            "scenario_912",
            "scenario_913",
            "scenario_914",
            "scenario_9200",
        )
        retiredCodes.forEach { code ->
            assertTrue(
                ScenarioCatalogServiceTest::class.java.getResource("/scenario/$code.json") == null,
                "$code.json 은퇴 시나리오가 클래스패스에 남았다",
            )
            assertTrue(byCode[code] == null, "은퇴 시나리오 $code 가 런타임 목록에 노출됐다")
        }
        assertEquals("휘하 예주 조각 (합성 운영 후보)", byCode["scenario_990002"]?.title)
        assertEquals(
            scenarios.map { it.code }.sortedBy { it.removePrefix("scenario_").toInt() },
            scenarios.map { it.code },
        )
    }

    @Test
    fun `approval does not expose absent or retired historical resources`() {
        val retired = """{"title":"unmaterialized fixture","worldFormat":"unprepared"}"""
        val rows = catalog.options(listOf(resource("scenario_1010", retired), resource("scenario_3190"))).scenarios
        assertEquals(listOf("scenario_3190"), rows.map { it.code })
    }

    @Test
    fun `all seventeen approved codes accept prepared contract fixtures without a two-code whitelist`() {
        // Synthetic catalog contracts do not claim that historical scenario data has been materialized.
        val manifest = mapper.readTree(requireNotNull(javaClass.getResourceAsStream("/scenario-reset-catalog.json")))
        val codes = manifest.path("approvedCodes").map { it.asText() }
        assertEquals(17, codes.size)
        assertEquals("scenario_3190", manifest.path("defaultCode").asText())
        assertEquals(codes, catalog.options(codes.map { resource(it) }).scenarios.map { it.code })
    }

    @Test
    fun `prepared unapproved QA and invalid paths are not selectable`() {
        assertTrue(catalog.options(listOf(resource("scenario_9200"), resource("scenario_999999"))).scenarios.isEmpty())
        assertFalse(catalog.isSelectable("scenario_9200"))
        assertFalse(catalog.isSelectable("../../scenario_3190"))
    }

    @Test
    fun `selected prepared scenario passes the actual fresh importer before image promotion`() {
        val selected = System.getenv("PEP_SCENARIO_CODE")?.takeIf { it.isNotBlank() }
        val codes = if (selected == null) catalog.list().scenarios.map { it.code } else listOf(selected)
        var root = java.nio.file.Path.of("").toAbsolutePath()
        while (!java.nio.file.Files.isRegularFile(root.resolve("settings.gradle.kts"))) {
            root = requireNotNull(root.parent) { "repository root unavailable" }
        }
        val cities = ScenarioJson.loadMapCities(requireNotNull(javaClass.getResourceAsStream("/map/han-world-v3.json"))
            .bufferedReader().use { it.readText() })
        for (code in codes) {
            assertTrue(catalog.isSelectable(code), "unprepared selection $code")
            val scenario = ScenarioJson.loadScenario(requireNotNull(javaClass.getResourceAsStream("/scenario/$code.json"))
                .bufferedReader().use { it.readText() })
            for (extended in listOf(false, true)) {
                val selectedWorld = opensamguk.infra.seed.ScenarioImporter(
                    scenario, cities, scenarioCode = code, scenarioNumber = code.removePrefix("scenario_").toInt(),
                    turnTerm = 60, maxGeneral = 50, firstTurnImmediate = true,
                    extendedGeneral = extended, blockGeneralCreate = 1, artifactsRoot = root,
                    onFreshWorldArtifacts = {},
                ).captureFreshSelectionReadOnly()
                assertEquals(opensamguk.logic.world.WorldMapVariant.PROVINCE_WORLD, selectedWorld.variant)
            }
        }
    }

    @Test
    fun `missing importer declarations and malformed resources stay unavailable`() {
        for (key in listOf("worldFormat", "seedContract", "warehouses", "rulers", "personPolicies")) {
            val node = mapper.readTree(preparedJson()).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            node.remove(key)
            assertTrue(catalog.options(listOf(resource("scenario_1010", node.toString()))).scenarios.isEmpty(), key)
        }
        assertTrue(catalog.options(listOf(resource("scenario_1010", "{"))).scenarios.isEmpty())
    }
}
