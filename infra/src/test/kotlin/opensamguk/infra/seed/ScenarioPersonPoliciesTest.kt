package opensamguk.infra.seed

import opensamguk.infra.persistence.MetaJson
import kotlin.test.*

internal object SyntheticScenario {
    fun person(name: String = "QA 주공", nation: Int = 1): List<Any?> = listOf(0, name, null, nation, null, 60, 61, 62, 12, 170, 280, null, null, null, 63, 64)
    fun policy(name: String = "QA 주공", officerId: Int = 1): Map<String, Any?> = linkedMapOf(
        "name" to name, "statSourceId" to "synthetic-qa:court", "statSourceRevision" to "v1",
        "officerId" to officerId, "acceptsEnlistment" to true,
        "stats" to linkedMapOf("leadership" to 60, "strength" to 61, "intelligence" to 62, "politics" to 63, "charm" to 64))
    fun root(): Map<String, Any?> = linkedMapOf("title" to "Synthetic QA", "startYear" to 200,
        "seedContract" to mapOf("activeGenerals" to mapOf("base" to 1, "extended" to 1)),
        "worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "map" to mapOf("mapName" to "han-world-v3"),
        "nation" to listOf(listOf("QA 세력", "#123456", 1000, 1000, "synthetic QA", 0, null, 1, listOf("허창"))),
        "general" to listOf(person()), "lords" to listOf("QA 주공"), "personPolicies" to listOf(policy()))
    fun parse(root: Map<String, Any?> = root()): Scenario = ScenarioJson.loadScenario(MetaJson.encode(root))
}

class ScenarioPersonPoliciesTest {
    @Test fun `explicit synthetic declaration binds five stats and initial capacity without fallback`() {
        val scenario = SyntheticScenario.parse()
        val general = scenario.generals.single()
        val policy = assertNotNull(general.personPolicy)
        assertEquals(30, policy.renownCapacity)
        assertEquals("synthetic-qa:court", policy.statSourceId)
        assertTrue(policy.acceptsEnlistment)
        ScenarioPersonPolicies.validate(general)
        assertNull(SyntheticScenario.parse(SyntheticScenario.root() - "personPolicies").generals.single().personPolicy)
    }
    @Test fun `reviewed 190 source binds stable officer identity and rejects a changed revision`() {
        val root = SyntheticScenario.root()
        val person = SyntheticScenario.person().toMutableList().also { it[2] = 10071 }
        val historical = SyntheticScenario.policy(officerId = 10071) + mapOf(
            "statSourceId" to "rtk14-wikiwiki:190.1",
            "statSourceRevision" to "sha256:5f511438e36bd5b673370928365c8cef78d464a7683ec78105280d310e4a68fd",
        )
        val scenario = SyntheticScenario.parse(root + mapOf(
            "general" to listOf(person), "personPolicies" to listOf(historical)))
        ScenarioPersonPolicies.validate(scenario.generals.single())
        assertFailsWith<IllegalArgumentException> { SyntheticScenario.parse(root + mapOf(
            "general" to listOf(person),
            "personPolicies" to listOf(historical + ("statSourceRevision" to "sha256:changed")))) }
        val changedPicture = person.toMutableList().also { it[2] = 10072 }
        assertFailsWith<IllegalArgumentException> {
            ScenarioPersonPolicies.validate(SyntheticScenario.parse(root + mapOf(
                "general" to listOf(changedPicture), "personPolicies" to listOf(historical))).generals.single())
        }
    }
    @Test fun `profile identity duplicates and unsupported historical claims are rejected`() {
        val root = SyntheticScenario.root(); val policy = SyntheticScenario.policy()
        val invalid = listOf(
            root + ("ruleProfile" to "SAMMO"), root + ("personPolicies" to null),
            root + ("personPolicies" to listOf(policy, policy)),
            root + ("personPolicies" to listOf(policy + ("name" to "unknown"))),
            root + ("personPolicies" to listOf(policy + ("statSourceId" to "rtk14:unverified"))),
            root + ("personPolicies" to listOf(policy + ("acceptsEnlistment" to "true"))),
            root + ("personPolicies" to listOf(policy + ("renownCapacity" to 999))),
            root + ("general" to listOf(SyntheticScenario.person(), SyntheticScenario.person())),
            root + ("personPolicies" to listOf(policy, policy + ("name" to "second"))),
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { SyntheticScenario.parse(it) } }
    }
    @Test fun `missing or changed stats never use importer defaults as evidence`() {
        val root = SyntheticScenario.root(); val policy = SyntheticScenario.policy()
        for (i in listOf(5, 6, 7, 14, 15)) {
            val tuple = SyntheticScenario.person().toMutableList(); tuple[i] = null
            assertFailsWith<IllegalArgumentException> { SyntheticScenario.parse(root + ("general" to listOf(tuple))) }
        }
        assertFailsWith<IllegalArgumentException> {
            SyntheticScenario.parse(root + ("personPolicies" to listOf(policy + ("stats" to mapOf("leadership" to 60)))))
        }
        val tuple = SyntheticScenario.person().toMutableList(); tuple[15] = 65
        assertFailsWith<IllegalArgumentException> { SyntheticScenario.parse(root + ("general" to listOf(tuple))) }
        val general = SyntheticScenario.parse().generals.single()
        assertFailsWith<IllegalArgumentException> { ScenarioPersonPolicies.validate(general.copy(charm = 99)) }
    }
    @Test fun `importer validates selected roster overrides before writes`() {
        val scenario = SyntheticScenario.parse()
        val altered = scenario.generals.single().copy(charm = 99)
        assertFailsWith<IllegalArgumentException> {
            ScenarioImporter(scenario.copy(generals = emptyList(), baseGenerals = listOf(altered)), emptyList()).validateSeedContract()
        }
    }

    @Test fun `identical selected roster duplicates cannot be removed before uniqueness validation`() {
        val scenario = SyntheticScenario.parse()
        val person = scenario.generals.single().copy(lord = false)
        val duplicated = scenario.copy(generals = emptyList(), baseGenerals = listOf(person, person),
            seedContract = ScenarioSeedContract(ActiveGeneralContract(2, 2)))
        val error = assertFailsWith<IllegalArgumentException> { ScenarioImporter(duplicated, emptyList()).validateSeedContract() }
        assertTrue(error.message.orEmpty().contains("Duplicate person policy in roster"))
    }

    @Test fun `isolated browser fixture supplies explicit policy and valid seed counts`() {
        val scenario = ScenarioJson.loadScenario(java.nio.file.Files.readString(
            java.nio.file.Path.of("../tools/e2e/fixtures/court/scenario_990001.json")))
        ScenarioImporter(scenario, emptyList(), scenarioCode = "scenario_990001").validateSeedContract()
        assertEquals(1, scenario.generals.size)
        assertEquals(1, scenario.generals.count { it.lord == true })
        assertTrue(scenario.generals.single().personPolicy!!.acceptsEnlistment)
        assertEquals("허창", scenario.generals.single().locatedCity)
    }

}
