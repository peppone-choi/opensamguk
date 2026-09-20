package opensamguk.infra.seed

import opensamguk.infra.persistence.MetaJson
import kotlin.test.*

internal object HwihaSyntheticScenario {
    fun person(name: String = "QA 주공", nation: Int = 1): List<Any?> = listOf(0, name, null, nation, null, 60, 61, 62, 12, 170, 280, null, null, null, 63, 64)
    fun policy(name: String = "QA 주공", officerId: Int = 1): Map<String, Any?> = linkedMapOf(
        "name" to name, "statSourceId" to "synthetic-qa:court", "statSourceRevision" to "v1",
        "officerId" to officerId, "acceptsEnlistment" to true,
        "stats" to linkedMapOf("leadership" to 60, "strength" to 61, "intelligence" to 62, "politics" to 63, "charm" to 64))
    fun root(): Map<String, Any?> = linkedMapOf("title" to "Synthetic QA", "startYear" to 200,
        "seedContract" to mapOf("activeGenerals" to mapOf("base" to 1, "extended" to 1)),
        "ruleProfile" to "HWIHA", "map" to mapOf("mapName" to "han-world-v3"),
        "nation" to listOf(listOf("QA 세력", "#123456", 1000, 1000, "synthetic QA", 0, null, 1, listOf("허창"))),
        "general" to listOf(person()), "hwihaLords" to listOf("QA 주공"), "hwihaPersonPolicies" to listOf(policy()))
    fun parse(root: Map<String, Any?> = root()): Scenario = ScenarioJson.loadScenario(MetaJson.encode(root))
}

class HwihaScenarioPersonPoliciesTest {
    @Test fun `explicit synthetic declaration binds five stats and initial capacity without fallback`() {
        val scenario = HwihaSyntheticScenario.parse()
        val general = scenario.generals.single()
        val policy = assertNotNull(general.hwihaPersonPolicy)
        assertEquals(30, policy.renownCapacity)
        assertEquals("synthetic-qa:court", policy.statSourceId)
        assertTrue(policy.acceptsEnlistment)
        HwihaScenarioPersonPolicies.validate(general)
        assertNull(HwihaSyntheticScenario.parse(HwihaSyntheticScenario.root() - "hwihaPersonPolicies").generals.single().hwihaPersonPolicy)
    }
    @Test fun `profile identity duplicates and unsupported historical claims are rejected`() {
        val root = HwihaSyntheticScenario.root(); val policy = HwihaSyntheticScenario.policy()
        val invalid = listOf(
            root + ("ruleProfile" to "SAMMO"), root + ("hwihaPersonPolicies" to null),
            root + ("hwihaPersonPolicies" to listOf(policy, policy)),
            root + ("hwihaPersonPolicies" to listOf(policy + ("name" to "unknown"))),
            root + ("hwihaPersonPolicies" to listOf(policy + ("statSourceId" to "rtk14:unverified"))),
            root + ("hwihaPersonPolicies" to listOf(policy + ("acceptsEnlistment" to "true"))),
            root + ("hwihaPersonPolicies" to listOf(policy + ("renownCapacity" to 999))),
            root + ("general" to listOf(HwihaSyntheticScenario.person(), HwihaSyntheticScenario.person())),
            root + ("hwihaPersonPolicies" to listOf(policy, policy + ("name" to "second"))),
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { HwihaSyntheticScenario.parse(it) } }
    }
    @Test fun `missing or changed stats never use importer defaults as evidence`() {
        val root = HwihaSyntheticScenario.root(); val policy = HwihaSyntheticScenario.policy()
        for (i in listOf(5, 6, 7, 14, 15)) {
            val tuple = HwihaSyntheticScenario.person().toMutableList(); tuple[i] = null
            assertFailsWith<IllegalArgumentException> { HwihaSyntheticScenario.parse(root + ("general" to listOf(tuple))) }
        }
        assertFailsWith<IllegalArgumentException> {
            HwihaSyntheticScenario.parse(root + ("hwihaPersonPolicies" to listOf(policy + ("stats" to mapOf("leadership" to 60)))))
        }
        val tuple = HwihaSyntheticScenario.person().toMutableList(); tuple[15] = 65
        assertFailsWith<IllegalArgumentException> { HwihaSyntheticScenario.parse(root + ("general" to listOf(tuple))) }
        val general = HwihaSyntheticScenario.parse().generals.single()
        assertFailsWith<IllegalArgumentException> { HwihaScenarioPersonPolicies.validate(general.copy(charm = 99)) }
    }
    @Test fun `importer validates selected roster overrides before writes`() {
        val scenario = HwihaSyntheticScenario.parse()
        val altered = scenario.generals.single().copy(charm = 99)
        assertFailsWith<IllegalArgumentException> {
            ScenarioImporter(scenario.copy(generals = emptyList(), baseGenerals = listOf(altered)), emptyList()).validateSeedContract()
        }
    }

    @Test fun `identical selected roster duplicates cannot be removed before uniqueness validation`() {
        val scenario = HwihaSyntheticScenario.parse()
        val person = scenario.generals.single().copy(hwihaLord = false)
        val duplicated = scenario.copy(generals = emptyList(), baseGenerals = listOf(person, person),
            seedContract = ScenarioSeedContract(ActiveGeneralContract(2, 2)))
        val error = assertFailsWith<IllegalArgumentException> { ScenarioImporter(duplicated, emptyList()).validateSeedContract() }
        assertTrue(error.message.orEmpty().contains("Duplicate person policy in roster"))
    }

    @Test fun `isolated browser fixture supplies explicit policy and valid seed counts`() {
        val scenario = ScenarioJson.loadScenario(java.nio.file.Files.readString(
            java.nio.file.Path.of("../tools/e2e/fixtures/hwiha-court/scenario_990001.json")))
        ScenarioImporter(scenario, emptyList(), scenarioCode = "scenario_990001").validateSeedContract()
        assertEquals(1, scenario.generals.size)
        assertEquals(1, scenario.generals.count { it.hwihaLord == true })
        assertTrue(scenario.generals.single().hwihaPersonPolicy!!.acceptsEnlistment)
        assertEquals("허창", scenario.generals.single().locatedCity)
    }

}
