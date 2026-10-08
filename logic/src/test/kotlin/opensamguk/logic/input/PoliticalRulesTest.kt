package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticProjection

import kotlin.test.*

class PoliticalRulesTest {
    private fun person(nation: Int = 0, renown: Int = 50, lord: Boolean = false) =
        DomesticPerson(1, "장수", nation, true, 0, if (lord) 12 else 1, 60, 60, 60, 60, 60,
            "province", false, mapOf(LordStatus.META_KEY to lord,
                PersonPolicyState.META_KEY to PersonPolicyState(renown, true,
                    "test", "1", 1).toMetaValue()), troopId = 0, spatialStateAvailable = true)
    private fun state(actor: DomesticPerson, countyOwner: Int = 0) =
        DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1), listOf(actor), emptyList(),
            listOf(DomesticCounty(10, "현", countyOwner, "province", "군", emptyMap())),
            listOf(DomesticNation(1, "국", 10, emptyMap())), setOf("province"), troops = emptyList())
    private fun check(inputId: String, state: DomesticProjection) =
        PoliticalRules.assess(PoliticalRequest(1, inputId), state)

    @Test fun `rise has no renown threshold while independence keeps its proposed threshold and county rule`() {
        assertIs<PoliticalAssessment.Eligible>(check(PoliticalInput.RISE, state(person())))
        for (renown in listOf(0, 49)) assertIs<PoliticalAssessment.Eligible>(
            check(PoliticalInput.RISE, state(person(renown = renown))))
        assertEquals(PoliticalFailure.COUNTY_NOT_AVAILABLE,
            assertIs<PoliticalAssessment.Rejected>(check(PoliticalInput.RISE,
                state(person(), countyOwner = 1))).reason)
        assertIs<PoliticalAssessment.Eligible>(check(PoliticalInput.INDEPENDENCE,
            state(person(nation = 1), countyOwner = 1)))
        assertEquals(PoliticalFailure.INSUFFICIENT_RENOWN,
            assertIs<PoliticalAssessment.Rejected>(check(PoliticalInput.INDEPENDENCE,
                state(person(nation = 1, renown = 49), countyOwner = 1))).reason)
        assertEquals(PoliticalFailure.COUNTY_NOT_AVAILABLE,
            assertIs<PoliticalAssessment.Rejected>(check(PoliticalInput.INDEPENDENCE,
                state(person(nation = 1), countyOwner = 0))).reason)
    }

    @Test fun `rise does not read absent or malformed renown and has no opening period restriction`() {
        for (meta in listOf(mapOf<String, Any?>(LordStatus.META_KEY to false),
            mapOf(LordStatus.META_KEY to false, PersonPolicyState.META_KEY to "malformed"))) {
            for (phase in listOf(Phase(200, 1, 1), Phase(300, 12, 3))) {
                assertIs<PoliticalAssessment.Eligible>(check(PoliticalInput.RISE,
                    state(person().copy(meta = meta)).copy(now = phase)))
            }
        }
    }

    @Test fun `resign needs a subject and dissolve needs an existing lord nation`() {
        assertEquals(PoliticalFailure.NOT_A_SUBJECT,
            assertIs<PoliticalAssessment.Rejected>(check(PoliticalInput.RESIGN,
                state(person()))).reason)
        assertIs<PoliticalAssessment.Eligible>(check(PoliticalInput.RESIGN,
            state(person(nation = 1), countyOwner = 1)))
        assertEquals(PoliticalFailure.ALREADY_LORD,
            assertIs<PoliticalAssessment.Rejected>(check(PoliticalInput.RESIGN,
                state(person(nation = 1, lord = true), countyOwner = 1))).reason)
        assertEquals(PoliticalFailure.NOT_LORD,
            assertIs<PoliticalAssessment.Rejected>(check(PoliticalInput.DISSOLVE,
                state(person(nation = 1), countyOwner = 1))).reason)
        assertIs<PoliticalAssessment.Eligible>(check(PoliticalInput.DISSOLVE,
            state(person(nation = 1, lord = true), countyOwner = 1)))
    }

    @Test fun `historical oath bond prevents a second oath`() {
        val issuer = person(nation = 1).copy(meta = OathBonds.withBond(person(nation = 1).meta, 2))
        val target = person(nation = 1).copy(id = 2, name = "상대")
        val snapshot = state(issuer, countyOwner = 1).copy(people = listOf(issuer, target))
        assertEquals(PoliticalFailure.ALREADY_BOUND,
            PoliticalRules.assessConsent(target.id,
                PoliticalConsent(issuer.id, PoliticalInput.OATH, true), snapshot))
        assertTrue(OathBonds.META_KEY in issuer.meta)
    }
}
