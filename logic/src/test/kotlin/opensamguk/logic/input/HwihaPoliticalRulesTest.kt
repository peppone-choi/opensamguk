package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticProjection

import kotlin.test.*

class HwihaPoliticalRulesTest {
    private fun person(nation: Int = 0, renown: Int = 50, lord: Boolean = false) =
        DomesticPerson(1, "장수", nation, true, 0, if (lord) 12 else 1, 60, 60, 60, 60, 60,
            "province", false, mapOf(HwihaLordStatus.META_KEY to lord,
                HwihaPersonPolicyState.META_KEY to HwihaPersonPolicyState(renown, true,
                    "test", "1", 1).toMetaValue()))
    private fun state(actor: DomesticPerson, countyOwner: Int = 0) =
        DomesticProjection(RuleProfile.HWIHA, HwihaPhase(200, 1, 1), listOf(actor), emptyList(),
            listOf(DomesticCounty(10, "현", countyOwner, "province", "군", emptyMap())),
            listOf(DomesticNation(1, "국", 10, emptyMap())), setOf("province"))
    private fun check(inputId: String, state: DomesticProjection) =
        HwihaPoliticalRules.assess(HwihaPoliticalRequest(1, inputId), state)

    @Test fun `rise and independence require renown fifty and the current county`() {
        assertIs<HwihaPoliticalAssessment.Eligible>(check(HwihaPoliticalInput.RISE, state(person())))
        assertEquals(HwihaPoliticalFailure.INSUFFICIENT_RENOWN,
            assertIs<HwihaPoliticalAssessment.Rejected>(check(HwihaPoliticalInput.RISE,
                state(person(renown = 49)))).reason)
        assertEquals(HwihaPoliticalFailure.COUNTY_NOT_AVAILABLE,
            assertIs<HwihaPoliticalAssessment.Rejected>(check(HwihaPoliticalInput.RISE,
                state(person(), countyOwner = 1))).reason)
        assertIs<HwihaPoliticalAssessment.Eligible>(check(HwihaPoliticalInput.INDEPENDENCE,
            state(person(nation = 1), countyOwner = 1)))
        assertEquals(HwihaPoliticalFailure.COUNTY_NOT_AVAILABLE,
            assertIs<HwihaPoliticalAssessment.Rejected>(check(HwihaPoliticalInput.INDEPENDENCE,
                state(person(nation = 1), countyOwner = 0))).reason)
    }

    @Test fun `resign needs a subject and dissolve needs an existing lord nation`() {
        assertEquals(HwihaPoliticalFailure.NOT_A_SUBJECT,
            assertIs<HwihaPoliticalAssessment.Rejected>(check(HwihaPoliticalInput.RESIGN,
                state(person()))).reason)
        assertIs<HwihaPoliticalAssessment.Eligible>(check(HwihaPoliticalInput.RESIGN,
            state(person(nation = 1), countyOwner = 1)))
        assertEquals(HwihaPoliticalFailure.NOT_LORD,
            assertIs<HwihaPoliticalAssessment.Rejected>(check(HwihaPoliticalInput.DISSOLVE,
                state(person(nation = 1), countyOwner = 1))).reason)
        assertIs<HwihaPoliticalAssessment.Eligible>(check(HwihaPoliticalInput.DISSOLVE,
            state(person(nation = 1, lord = true), countyOwner = 1)))
    }

    @Test fun `historical oath bond prevents a second oath`() {
        val issuer = person(nation = 1).copy(meta = HwihaOathBonds.withBond(person(nation = 1).meta, 2))
        val target = person(nation = 1).copy(id = 2, name = "상대")
        val snapshot = state(issuer, countyOwner = 1).copy(people = listOf(issuer, target))
        assertEquals(HwihaPoliticalFailure.ALREADY_BOUND,
            HwihaPoliticalRules.assessConsent(target.id,
                HwihaPoliticalConsent(issuer.id, HwihaPoliticalInput.OATH, true), snapshot))
        assertTrue(HwihaOathBonds.META_KEY in issuer.meta)
    }
}
