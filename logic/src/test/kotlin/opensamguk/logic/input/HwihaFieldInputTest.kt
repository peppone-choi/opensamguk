package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import opensamguk.logic.economy.HwihaResources

class HwihaFieldInputTest {
    @Test fun `field input accepts only an empty object for all eight actions`() {
        for (inputId in HwihaFieldInput.INPUT_IDS) {
            assertEquals(HwihaFieldRequest(7, inputId), HwihaFieldInput.parse(7, inputId, "{}"))
            assertNull(HwihaFieldInput.parse(7, inputId, "{\"countyId\":1}"))
            assertNull(HwihaFieldInput.parse(7, inputId, "{} trailing"))
            assertNull(HwihaFieldInput.parse(7, inputId, "{\"x\":1,\"x\":2}"))
        }
        assertNull(HwihaFieldInput.parse(7, "action.unknown", "{}"))
    }

    private val person = DomesticPerson(7, "장수", 2, true, 2, 1, 50, 50, 50, 50, 50,
        "province-a", false, emptyMap())
    private val county = DomesticCounty(11, "현", 2, "province-a", "군", emptyMap())
    private val state = HwihaDomesticProjection(RuleProfile.HWIHA, HwihaPhase(200, 1, 1),
        listOf(person), emptyList(), listOf(county), emptyList(), setOf("province-a"))
    private val request = HwihaFieldRequest(7, HwihaFieldInput.FARM)

    @Test fun `field action resolves saved position and refuses a different owner's county`() {
        assertEquals(11, assertIs<HwihaFieldAssessment.Eligible>(HwihaFieldRules.assess(request, state)).county.id)
        assertEquals(HwihaFieldFailure.FOREIGN_COUNTY,
            assertIs<HwihaFieldAssessment.Rejected>(HwihaFieldRules.assess(request,
                state.copy(counties = listOf(county.copy(nationId = 3))))).reason)
        assertEquals(HwihaFieldFailure.POSITION_UNAVAILABLE,
            assertIs<HwihaFieldAssessment.Rejected>(HwihaFieldRules.assess(request,
                state.copy(people = listOf(person.copy(node = null))))).reason)
        assertEquals(HwihaFieldFailure.BATTLE_PENDING,
            assertIs<HwihaFieldAssessment.Rejected>(HwihaFieldRules.assess(request,
                state.copy(people = listOf(person.copy(inBattle = true))))).reason)
    }

    @Test fun `shared economy assessment rejects a short warehouse before effect`() {
        val levels = HwihaCountyLevels(50_000, 100_000, 100, 1000, 100, 1000, 100, 1000, 50.0,
            100, 1000, 100, 1000)
        val design = HwihaDomesticDesign.CANON
        val short = HwihaFieldRules.assessEconomy(HwihaFieldInput.FORTIFY, person, county.id,
            levels, HwihaResources(money = 9_999, timber = 500), design)
        assertEquals(HwihaFieldFailure.INSUFFICIENT_STOCK,
            assertIs<HwihaFieldEconomyAssessment.Rejected>(short).reason)
        val ready = HwihaFieldRules.assessEconomy(HwihaFieldInput.FORTIFY, person, county.id,
            levels, HwihaResources(money = 10_000, timber = 500), design)
        assertEquals(150, assertIs<HwihaFieldEconomyAssessment.Eligible>(ready).outcome.levels.defence)
    }
}
