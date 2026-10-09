package opensamguk.infra.seed

import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.seed.ScenarioPersonPolicies.HierarchySource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ScenarioHierarchyInventoryTest {
    private val repo = Path.of("..")
    private val archiveCodes = listOf(1010, 1020, 1021, 1030, 1031, 1040, 1041,
        1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120)
    private val codes = archiveCodes + listOf(3190, 990002)

    private fun input(code: Int): Pair<Map<String, Any?>, Scenario?> {
        val path = repo.resolve(if (code in archiveCodes) "data/archive/scenarios/scenario_$code.json"
            else "infra/src/main/resources/scenario/scenario_$code.json")
        assertTrue(Files.isRegularFile(path), "Missing inventory input: $path")
        val text = Files.readString(path)
        return MetaJson.decode(text) to if (code in archiveCodes) null else ScenarioJson.loadScenario(text)
    }

    private fun threeLevelRoot(): Map<String, Any?> {
        fun person(name: String, lord: Boolean = false) = SyntheticScenario.person(name)
            .toMutableList().also { it[8] = if (lord) 12 else 1 }
        return SyntheticScenario.root() + mapOf(
            "general" to listOf(person("QA 주공", true), person("QA 부장"), person("QA 휘하")),
            "personPolicies" to listOf(SyntheticScenario.policy(), SyntheticScenario.policy("QA 부장", 2),
                SyntheticScenario.policy("QA 휘하", 3)),
            "retainers" to listOf(mapOf("master" to "QA 주공", "general" to "QA 부장"),
                mapOf("master" to "QA 부장", "general" to "QA 휘하")),
            "seedContract" to mapOf("activeGenerals" to mapOf("base" to 3, "extended" to 3)),
        )
    }

    @Test fun `all seventeen actual files have a bounded inventory without inventing historical links`() {
        val catalog = MetaJson.decode(Files.readString(repo.resolve(
            "app/gateway-api/src/main/resources/scenario-reset-catalog.json")))
        assertEquals(codes.map { "scenario_$it" }, catalog["approvedCodes"])
        val inventory = codes.map { code ->
            val (root, parsed) = input(code)
            ScenarioPersonPolicies.hierarchyInventory(code, root, parsed)
        }
        assertEquals(17, inventory.size)
        assertEquals(codes, inventory.map { it.code })
        for (row in inventory.filter { it.code in archiveCodes }) {
            assertEquals(HierarchySource.UNKNOWN, row.source, "scenario ${row.code}")
            assertEquals(0, row.links)
            assertEquals(0, row.rulerLinks)
            assertEquals(0, row.maxDepth)
            assertEquals(emptySet(), row.policyFamilies)
            assertTrue(row.reason.contains("No explicit hierarchy declaration"))
            val (root, parsed) = input(row.code)
            assertFalse("retainers" in root)
            assertNull(parsed)
        }
        val game = inventory.single { it.code == 3190 }
        assertEquals(HierarchySource.GAME_DECLARATION, game.source)
        assertEquals(228, game.links)
        assertEquals(228, game.rulerLinks)
        assertEquals(1, game.maxDepth)
        assertEquals(setOf("rtk14-workbook"), game.policyFamilies)
        assertTrue(game.reason.contains("not per-link historical evidence"))
        val fixture = inventory.single { it.code == 990002 }
        assertEquals(HierarchySource.SYNTHETIC_FIXTURE, fixture.source)
        assertEquals(0, fixture.links)
        assertEquals(0, fixture.rulerLinks)
        assertEquals(0, fixture.maxDepth)
        assertEquals(setOf("synthetic-qa"), fixture.policyFamilies)
        assertTrue(inventory.none { it.source == HierarchySource.HISTORICAL_SOURCE })
    }

    @Test fun `explicit synthetic three level fixture counts its direct ruler link and two edge depth`() {
        val root = threeLevelRoot()
        val parsed = SyntheticScenario.parse(root)
        ScenarioImporter(parsed, emptyList()).validateSeedContract()
        val row = ScenarioPersonPolicies.hierarchyInventory(990003, root, parsed)
        assertEquals(HierarchySource.SYNTHETIC_FIXTURE, row.source)
        assertEquals(2, row.links)
        assertEquals(1, row.rulerLinks)
        assertEquals(2, row.maxDepth)
        assertEquals(setOf("synthetic-qa"), row.policyFamilies)
        assertEquals(30, parsed.generals.single { it.name == "QA 부장" }.personPolicy!!.renownCapacity)
    }

    @Test fun `every actual input and synthetic hierarchy rejects historical and mismatched source claims`() {
        val synthetic = threeLevelRoot()
        val inputs = codes.map { code -> code to input(code) } +
            (990003 to (synthetic to SyntheticScenario.parse(synthetic)))
        for ((code, pair) in inputs) {
            val (root, parsed) = pair
            val expected = ScenarioPersonPolicies.hierarchyInventory(code, root, parsed)
            for (claim in HierarchySource.entries) {
                if (claim == expected.source) {
                    assertEquals(expected, ScenarioPersonPolicies.requireHierarchyClaim(code, root, parsed, claim))
                } else {
                    val error = assertFailsWith<IllegalArgumentException>("scenario $code claim $claim") {
                        ScenarioPersonPolicies.requireHierarchyClaim(code, root, parsed, claim)
                    }
                    if (claim == HierarchySource.HISTORICAL_SOURCE) {
                        assertTrue(error.message.orEmpty().contains("per-link evidence"))
                    }
                }
            }
        }
    }

    @Test fun `mixed empty and unclassified stat provenance stays unknown without losing declared structure`() {
        val root = threeLevelRoot()
        val parsed = SyntheticScenario.parse(root)
        val rtk14 = SyntheticScenario.policy("QA 부장", 10071) + mapOf(
            "statSourceId" to "rtk14-workbook:190.1",
            "statSourceRevision" to "sha256:bb8f6db3b5afe732cb5d019cd16e15b92dc1296530ab265f1f7577a04de34e7f",
        )
        val variants = listOf(
            (root + ("personPolicies" to listOf(SyntheticScenario.policy(), rtk14))) to
                setOf("rtk14-workbook", "synthetic-qa"),
            (root - "personPolicies") to emptySet(),
            (root + ("personPolicies" to emptyList<Any>())) to emptySet(),
            (root + ("personPolicies" to null)) to setOf("unclassified"),
            (root + ("personPolicies" to listOf(emptyMap<String, Any>()))) to setOf("unclassified"),
            (root + ("personPolicies" to listOf(rtk14 + ("statSourceRevision" to "unreviewed")))) to
                setOf("unclassified"),
            (root + ("personPolicies" to listOf(SyntheticScenario.policy() + ("statSourceId" to "synthetic-qa:")))) to
                setOf("unclassified"),
        )
        for ((variant, families) in variants) {
            val row = ScenarioPersonPolicies.hierarchyInventory(990003, variant, parsed)
            assertEquals(HierarchySource.UNKNOWN, row.source)
            assertEquals(families, row.policyFamilies)
            assertEquals(2, row.links)
            assertEquals(1, row.rulerLinks)
            assertEquals(2, row.maxDepth)
            assertFailsWith<IllegalArgumentException> {
                ScenarioPersonPolicies.requireHierarchyClaim(990003, variant, parsed, HierarchySource.GAME_DECLARATION)
            }
        }
    }

    @Test fun `hierarchy declarations require a matching parsed scenario even for an explicit empty list`() {
        val root = threeLevelRoot()
        val parsed = SyntheticScenario.parse(root)
        for (declared in listOf(root, root + ("retainers" to emptyList<Any>()))) {
            assertFailsWith<IllegalArgumentException> {
                ScenarioPersonPolicies.hierarchyInventory(990003, declared, null)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ScenarioPersonPolicies.hierarchyInventory(990003, root, parsed.copy(retainers = emptyList()))
        }
        assertFailsWith<IllegalArgumentException> {
            ScenarioPersonPolicies.hierarchyInventory(990003, root - "retainers", parsed)
        }
    }

    @Test fun `manually constructed cycle duplicate and cross nation links still use the existing forest validator`() {
        val root = threeLevelRoot()
        val parsed = SyntheticScenario.parse(root)
        val invalid = listOf(
            parsed.copy(retainers = listOf(ScenarioRetainer("QA 부장", "QA 휘하"), ScenarioRetainer("QA 휘하", "QA 부장"))),
            parsed.copy(retainers = parsed.retainers + parsed.retainers.first()),
            parsed.copy(baseGenerals = parsed.baseGenerals.map {
                if (it.name == "QA 휘하") it.copy(nationId = 2) else it
            }),
        )
        for ((index, scenario) in invalid.withIndex()) {
            val matching = root + ("retainers" to scenario.retainers.map {
                mapOf("general" to it.general, "master" to it.master)
            })
            val error = assertFailsWith<IllegalArgumentException> {
                ScenarioPersonPolicies.hierarchyInventory(990003, matching, scenario)
            }
            if (index == 0) assertEquals("retinue cycle", error.message)
        }
    }
}
