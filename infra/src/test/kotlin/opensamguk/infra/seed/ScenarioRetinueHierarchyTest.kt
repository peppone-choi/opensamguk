package opensamguk.infra.seed

import opensamguk.logic.renown.RenownRules
import kotlin.test.*

class ScenarioRetinueHierarchyTest {
    private fun person(name: String, lord: Boolean = false) = SyntheticScenario.person(name)
        .toMutableList().also { it[8] = if (lord) 12 else 1 }
    private fun root(): Map<String, Any?> = SyntheticScenario.root() + mapOf(
        "general" to listOf(person("QA 주공", true), person("QA 부장"), person("QA 휘하")),
        "personPolicies" to listOf(SyntheticScenario.policy(), SyntheticScenario.policy("QA 부장", 2),
            SyntheticScenario.policy("QA 휘하", 3)),
        "retainers" to listOf(mapOf("master" to "QA 주공", "general" to "QA 부장"),
            mapOf("master" to "QA 부장", "general" to "QA 휘하")),
        "seedContract" to mapOf("activeGenerals" to mapOf("base" to 3, "extended" to 3)),
    )

    @Test fun `explicit three level seed preserves edges and uses each ordinary owner's existing capacity`() {
        val parsed = SyntheticScenario.parse(root())
        assertEquals(listOf(ScenarioRetainer("QA 부장", "QA 주공"), ScenarioRetainer("QA 휘하", "QA 부장")),
            parsed.retainers)
        val cost = RenownRules.personCost(60, 61, 62, 63, 64)
        assertEquals(30 + cost, parsed.generals.single { it.name == "QA 주공" }.personPolicy!!.renownCapacity)
        assertEquals(30, parsed.generals.single { it.name == "QA 부장" }.personPolicy!!.renownCapacity)
        assertEquals(30, parsed.generals.single { it.name == "QA 휘하" }.personPolicy!!.renownCapacity)
        val importer = ScenarioImporter(parsed, emptyList())
        importer.validateSeedContract()
        assertEquals(parsed.retainers, importer.initialRetainers())
    }

    @Test fun `cycles dangling names duplicate ownership and ruler-as-child fail before any seed write`() {
        val valid = root()
        val invalid = listOf(
            listOf(ScenarioRetainer("QA 부장", "QA 휘하"), ScenarioRetainer("QA 휘하", "QA 부장")),
            listOf(ScenarioRetainer("QA 부장", "없는 장수")),
            listOf(ScenarioRetainer("QA 부장", "QA 부장")),
            listOf(ScenarioRetainer("QA 휘하", "QA 주공"), ScenarioRetainer("QA 휘하", "QA 부장")),
            listOf(ScenarioRetainer("QA 주공", "QA 부장")),
        )
        for (edges in invalid) {
            assertFailsWith<IllegalArgumentException> {
                SyntheticScenario.parse(valid + ("retainers" to edges.map {
                    mapOf("general" to it.general, "master" to it.master)
                }))
            }
        }
        val parsed = SyntheticScenario.parse(valid)
        for (edges in invalid) assertFailsWith<IllegalArgumentException> {
            ScenarioImporter(parsed.copy(retainers = edges), emptyList()).validateSeedContract()
        }
    }

    @Test fun `ordinary initial owner cannot obtain an automatic extra allowance or ignore over-capacity`() {
        val names = (1..5).map { "QA 하위$it" }
        val tooMany = root() + mapOf(
            "general" to listOf(person("QA 주공", true), person("QA 부장")) + names.map { person(it) },
            "personPolicies" to listOf(SyntheticScenario.policy(), SyntheticScenario.policy("QA 부장", 2)) +
                names.mapIndexed { index, name -> SyntheticScenario.policy(name, index + 3) },
            "retainers" to listOf(mapOf("master" to "QA 주공", "general" to "QA 부장")) +
                names.map { mapOf("master" to "QA 부장", "general" to it) },
            "seedContract" to mapOf("activeGenerals" to mapOf("base" to 7, "extended" to 7)),
        )
        assertFailsWith<IllegalArgumentException> { SyntheticScenario.parse(tooMany) }
        val parsed = SyntheticScenario.parse(root())
        val inflated = parsed.generals.map { if (it.name == "QA 부장")
            it.copy(personPolicy = it.personPolicy!!.copy(renownCapacity = 40)) else it }
        assertFailsWith<IllegalArgumentException> {
            ScenarioImporter(parsed.copy(generals = inflated, baseGenerals = inflated), emptyList()).validateSeedContract()
        }
    }

    @Test fun `seed links cannot cross nations or use a deferred superior`() {
        val parsed = SyntheticScenario.parse(root())
        val crossNation = parsed.generals.map { if (it.name == "QA 휘하") it.copy(nationId = 2) else it }
        assertFailsWith<IllegalArgumentException> {
            ScenarioImporter(parsed.copy(generals = crossNation, baseGenerals = crossNation), emptyList()).validateSeedContract()
        }
        val deferred = parsed.generals.map { if (it.name == "QA 부장") it.copy(bornYear = 200) else it }
        assertFailsWith<IllegalArgumentException> {
            ScenarioImporter(parsed.copy(generals = deferred, baseGenerals = deferred), emptyList()).validateSeedContract()
        }
    }
}
