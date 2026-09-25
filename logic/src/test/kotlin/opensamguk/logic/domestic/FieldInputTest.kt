package opensamguk.logic.domestic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.HwihaDeployedCorps
import opensamguk.logic.input.HwihaDeploymentState
import opensamguk.logic.input.HwihaPhase
import opensamguk.logic.input.RuleProfile

class FieldInputTest {
    @Test fun `field input accepts only an empty object for all eight actions`() {
        for (inputId in FieldInput.INPUT_IDS) {
            assertEquals(FieldRequest(7, inputId), FieldInput.parse(7, inputId, "{}"))
            assertNull(FieldInput.parse(7, inputId, "{\"countyId\":1}"))
            assertNull(FieldInput.parse(7, inputId, "{} trailing"))
            assertNull(FieldInput.parse(7, inputId, "{\"x\":1,\"x\":2}"))
        }
        assertNull(FieldInput.parse(7, "action.unknown", "{}"))
    }

    private val person = DomesticPerson(7, "장수", 2, true, 2, 1, 50, 50, 50, 50, 50,
        "province-a", false, emptyMap())
    private val county = DomesticCounty(11, "현", 2, "province-a", "군", emptyMap())
    private val state = DomesticProjection(RuleProfile.HWIHA, HwihaPhase(200, 1, 1),
        listOf(person), emptyList(), listOf(county), emptyList(), setOf("province-a"))
    private val request = FieldRequest(7, FieldInput.FARM)

    @Test fun `field action resolves saved position and refuses a different owner's county`() {
        assertEquals(11, assertIs<FieldAssessment.Eligible>(FieldRules.assess(request, state)).county.id)
        assertEquals(FieldFailure.FOREIGN_COUNTY,
            assertIs<FieldAssessment.Rejected>(FieldRules.assess(request,
                state.copy(counties = listOf(county.copy(nationId = 3))))).reason)
        assertEquals(FieldFailure.POSITION_UNAVAILABLE,
            assertIs<FieldAssessment.Rejected>(FieldRules.assess(request,
                state.copy(people = listOf(person.copy(node = null))))).reason)
        assertEquals(FieldFailure.BATTLE_PENDING,
            assertIs<FieldAssessment.Rejected>(FieldRules.assess(request,
                state.copy(people = listOf(person.copy(inBattle = true))))).reason)
        val deployed = HwihaDeploymentState(listOf(HwihaDeployedCorps("order-7", 7, 7, null, 2,
            listOf(1), state.now)))
        assertEquals(FieldFailure.CORPS_DEPLOYED,
            assertIs<FieldAssessment.Rejected>(FieldRules.assess(request,
                state.copy(people = listOf(person.copy(meta = mapOf(HwihaDeploymentState.META_KEY to
                    deployed.toMetaValue())))))).reason)
    }

    @Test fun `shared economy assessment rejects a short warehouse before effect`() {
        val levels = CountyLevels(50_000, 100_000, 100, 1000, 100, 1000, 100, 1000, 50.0,
            100, 1000, 100, 1000)
        val design = DomesticDesign.CANON
        val short = FieldRules.assessEconomy(FieldInput.FORTIFY, person, county.id,
            levels, Resources(money = 4_999, timber = 250), design)
        assertEquals(FieldFailure.INSUFFICIENT_STOCK,
            assertIs<FieldEconomyAssessment.Rejected>(short).reason)
        val ready = FieldRules.assessEconomy(FieldInput.FORTIFY, person, county.id,
            levels, Resources(money = 5_000, timber = 250), design)
        assertEquals(150, assertIs<FieldEconomyAssessment.Eligible>(ready).outcome.levels.defence)
        val capped = FieldRules.assessEconomy(FieldInput.FORTIFY, person, county.id,
            levels.copy(defence = 1000), Resources(), design)
        assertEquals(FieldFailure.AT_CAPACITY,
            assertIs<FieldEconomyAssessment.Rejected>(capped).reason)
    }
}
