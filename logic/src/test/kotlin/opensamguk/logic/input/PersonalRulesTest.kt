package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticProjection

import kotlin.test.*

class PersonalRulesTest {
    private fun state(person: DomesticPerson, profile: RuleProfile = RuleProfile.HWIHA) =
        DomesticProjection(profile, Phase(200, 1, 1), listOf(person), emptyList(),
            emptyList(), emptyList(), setOf("province"))

    private fun person(injury: Int = 0, fatigue: Int = 0, strength: Int = 70) =
        DomesticPerson(1, "actor", 1, true, 2, 1, 70, strength, 70, 70, 70,
            "province", false, mapOf(PersonalTravelCondition.META_KEY to
                PersonalTravelCondition(fatigue, 100).toMetaValue()), injury)

    @Test fun `personal actions use the actor location and current condition`() {
        assertIs<PersonalAssessment.Eligible>(PersonalRules.assess(
            PersonalRequest(1, PersonalInput.TRAVEL), state(person())))
        assertEquals(PersonalFailure.ALREADY_HEALTHY,
            assertIs<PersonalAssessment.Rejected>(PersonalRules.assess(
                PersonalRequest(1, PersonalInput.RECUPERATE), state(person()))).reason)
        assertIs<PersonalAssessment.Eligible>(PersonalRules.assess(
            PersonalRequest(1, PersonalInput.RECUPERATE), state(person(injury = 10))))
        assertIs<PersonalAssessment.Eligible>(PersonalRules.assess(
            PersonalRequest(1, PersonalInput.RECUPERATE), state(person(fatigue = 5))))
    }

    @Test fun `maxed training and battle are rejected by the shared rules`() {
        val request = PersonalRequest(1, PersonalInput.SELF_TRAIN, TrainingStat.STRENGTH)
        assertEquals(PersonalFailure.TRAINING_MAXED,
            assertIs<PersonalAssessment.Rejected>(PersonalRules.assess(request,
                state(person(strength = PersonalDesign.CANON.trainingStatCap)))).reason)
        assertEquals(PersonalFailure.BATTLE_PENDING,
            assertIs<PersonalAssessment.Rejected>(PersonalRules.assess(request,
                state(person().copy(inBattle = true)))).reason)
        assertEquals(PersonalFailure.POSITION_UNAVAILABLE,
            assertIs<PersonalAssessment.Rejected>(PersonalRules.assess(request,
                state(person().copy(node = null)))).reason)
    }
}
