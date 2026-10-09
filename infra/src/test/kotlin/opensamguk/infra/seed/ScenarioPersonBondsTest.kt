package opensamguk.infra.seed

import opensamguk.logic.content.PersonBondKind
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
        "general" to roster(), "lords" to listOf("첫째"),
        "rulers" to listOf(mapOf("nation" to "QA 세력", "general" to "첫째")),
        "personPolicies" to emptyList<Any>(),
        "personBonds" to rows)

    @Test fun `novel bond is distinct from book and volume historical evidence`() {
        val scenario = SyntheticScenario.parse(root(listOf(bond())))
        assertEquals(1, scenario.personBonds.getValue("첫째").size)
        assertEquals(setOf("novel:三國演義:第一回"), scenario.personBonds.getValue("첫째").single().evidenceIds)
    }

    @Test fun `materialized portrait filenames keep stable bond targets`() {
        val portraits = roster().map { row -> row.toMutableList().also { it[2] = "${it[2]}.png" } }
        val scenario = SyntheticScenario.parse(root(listOf(bond())) + ("general" to portraits))
        assertEquals(10853, scenario.personBonds.getValue("첫째").single().targetOfficerId)
    }

    @Test fun `all four person-target kinds retain officer IDs and evidence`() {
        val kinds = setOf(PersonBondKind.BLOOD_KIN, PersonBondKind.PATRONAGE,
            PersonBondKind.OATH, PersonBondKind.RENOWN)
        val scenario = SyntheticScenario.parse(root(kinds.map { bond() + ("kind" to it.name) }))
        assertEquals(kinds.map { ScenarioPersonBond(it, 10853, setOf("novel:三國演義:第一回")) }.toSet(),
            scenario.personBonds.getValue("첫째").toSet())
    }

    @Test fun `native county cannot be decoded as an officer even when the officer exists`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SyntheticScenario.parse(root(listOf(bond() + ("kind" to "NATIVE_COUNTY"))))
        }
        assertEquals("NATIVE_COUNTY cannot use targetOfficerId; a county target is required", error.message)
    }

    @Test fun `direct model construction and copy cannot bypass native county rejection`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ScenarioPersonBond(PersonBondKind.NATIVE_COUNTY, 10853, setOf("history:三國志:卷21"))
        }
        assertEquals("NATIVE_COUNTY cannot use targetOfficerId; a county target is required", error.message)
        val oath = ScenarioPersonBond(PersonBondKind.OATH, 10853, setOf("novel:三國演義:第一回"))
        assertFailsWith<IllegalArgumentException> { oath.copy(kind = PersonBondKind.NATIVE_COUNTY) }
    }

    @Test fun `county fields are not silently treated as supported scenario declarations`() {
        val native = bond() + ("kind" to "NATIVE_COUNTY")
        for (row in listOf(native + ("targetCountyId" to 1),
            (native - "targetOfficerId") + ("targetCountyId" to 1))) {
            val error = assertFailsWith<IllegalArgumentException> { SyntheticScenario.parse(root(listOf(row))) }
            assertEquals("Unexpected personBonds fields", error.message)
        }
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
