package opensamguk.logic.input

import kotlin.test.*

class HwihaPersonalRulesTest {
    private fun state(person: DomesticPerson, profile: RuleProfile = RuleProfile.HWIHA) =
        HwihaDomesticProjection(profile, HwihaPhase(200, 1, 1), listOf(person), emptyList(),
            emptyList(), emptyList(), setOf("province"))

    private fun person(injury: Int = 0, fatigue: Int = 0, strength: Int = 70) =
        DomesticPerson(1, "actor", 1, true, 2, 1, 70, strength, 70, 70, 70,
            "province", false, mapOf(HwihaPersonalTravelCondition.META_KEY to
                HwihaPersonalTravelCondition(fatigue, 100).toMetaValue()), injury)

    @Test fun `personal actions use the actor location and current condition`() {
        assertIs<HwihaPersonalAssessment.Eligible>(HwihaPersonalRules.assess(
            HwihaPersonalRequest(1, HwihaPersonalInput.TRAVEL), state(person())))
        assertEquals(HwihaPersonalFailure.ALREADY_HEALTHY,
            assertIs<HwihaPersonalAssessment.Rejected>(HwihaPersonalRules.assess(
                HwihaPersonalRequest(1, HwihaPersonalInput.RECUPERATE), state(person()))).reason)
        assertIs<HwihaPersonalAssessment.Eligible>(HwihaPersonalRules.assess(
            HwihaPersonalRequest(1, HwihaPersonalInput.RECUPERATE), state(person(injury = 10))))
        assertIs<HwihaPersonalAssessment.Eligible>(HwihaPersonalRules.assess(
            HwihaPersonalRequest(1, HwihaPersonalInput.RECUPERATE), state(person(fatigue = 5))))
    }

    @Test fun `maxed training and battle are rejected by the shared rules`() {
        val request = HwihaPersonalRequest(1, HwihaPersonalInput.SELF_TRAIN, HwihaTrainingStat.STRENGTH)
        assertEquals(HwihaPersonalFailure.TRAINING_MAXED,
            assertIs<HwihaPersonalAssessment.Rejected>(HwihaPersonalRules.assess(request,
                state(person(strength = HwihaPersonalDesign.CANON.trainingStatCap)))).reason)
        assertEquals(HwihaPersonalFailure.BATTLE_PENDING,
            assertIs<HwihaPersonalAssessment.Rejected>(HwihaPersonalRules.assess(request,
                state(person().copy(inBattle = true)))).reason)
        assertEquals(HwihaPersonalFailure.POSITION_UNAVAILABLE,
            assertIs<HwihaPersonalAssessment.Rejected>(HwihaPersonalRules.assess(request,
                state(person().copy(node = null)))).reason)
    }
}
