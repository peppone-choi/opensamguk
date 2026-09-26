package opensamguk.infra.seed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScenarioPersonBondsTest {
    private fun roster() = listOf(
        SyntheticScenario.person("첫째").toMutableList().also { it[2] = 10071 },
        SyntheticScenario.person("둘째").toMutableList().also { it[2] = 10853 },
    )
    private fun bond() = mapOf("name" to "첫째", "kind" to "OATH", "targetOfficerId" to 10853,
        "evidenceIds" to listOf("novel:三國演義:第一回"))
    private fun root(rows: List<Map<String, Any?>>) = SyntheticScenario.root() + mapOf(
        "general" to roster(), "lords" to listOf("첫째"), "personPolicies" to emptyList<Any>(),
        "personBonds" to rows)

    @Test fun `novel bond is distinct from book and volume historical evidence`() {
        val scenario = SyntheticScenario.parse(root(listOf(bond())))
        assertEquals(1, scenario.personBonds.getValue("첫째").size)
        assertEquals(setOf("novel:三國演義:第一回"), scenario.personBonds.getValue("첫째").single().evidenceIds)
    }

    @Test fun `missing target, duplicate, self and unlabelled evidence are rejected`() {
        val row = bond()
        val bad = listOf(
            listOf(row, row),
            listOf(row + ("targetOfficerId" to 99999)),
            listOf(row + ("targetOfficerId" to 10071)),
            listOf(row + ("evidenceIds" to listOf("三國演義"))),
            listOf(row + ("evidenceIds" to emptyList<String>())),
        )
        bad.forEach { assertFailsWith<IllegalArgumentException> { SyntheticScenario.parse(root(it)) } }
    }
}
