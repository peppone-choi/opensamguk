package opensamguk.logic.input

import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.domestic.*
import opensamguk.logic.economy.Resources
import kotlin.test.*

class DeclareWarPolicyTest {
    private val phase = Phase(200, 1, 1)
    private val args = """{"targetNationId":2}"""

    @Test fun `provisional declaration decisions are fixed in one place`() {
        assertEquals(Resources(money = 0, grain = 0, iron = 0, timber = 0, horses = 0), DeclareWarPolicy.RESOURCE_COST)
        assertEquals(24, DeclareWarPolicy.DECLARATION_TERM_MONTHS)
        assertTrue(DeclareWarPolicy.ALLOW_DURING_NON_AGGRESSION)
    }

    private fun state(relation: Int = DiplomacyState.TRADE, withEnvoy: Boolean = true,
        arrived: Boolean = true, ruler: Boolean = true): DomesticProjection {
        val order = PlacementOrder("envoy", 1, 5, PlacementPost.ENVOY, PlacementTarget.Nation(2), phase)
        val placement = PlacementState(ActivePlacement(order, phase, phase.takeIf { arrived }), null)
        val actor = DomesticPerson(1, "군주", 1, true, 2, if (ruler) 12 else 1,
            70, 70, 70, 70, 70, null, false, mapOf(LordStatus.META_KEY to ruler))
        return DomesticProjection(RuleProfile.HWIHA, phase,
            listOf(actor, actor.copy(id = 3, officerLevel = 1,
                meta = mapOf(PlacementState.META_KEY to placement.toMetaValue()))),
            if (withEnvoy) listOf(DomesticCard(5, 1, 3, "TEST")) else emptyList(), emptyList(),
            listOf(DomesticNation(1, "아국", null, emptyMap()), DomesticNation(2, "상대국", null, emptyMap())),
            emptySet(), diplomacy = listOf(DomesticDiplomacy(1, 2, relation, 0)))
    }

    private fun rejection(state: DomesticProjection, json: String = args) =
        assertIs<CourtAssessment.Rejected>(CourtRules.assess(1, DiplomacyInput.DECLARE_WAR, json, state)).reason

    @Test fun `declaration requires a ruler and an arrived envoy for a different nation`() {
        assertIs<CourtAssessment.Eligible>(CourtRules.assess(1, DiplomacyInput.DECLARE_WAR, args, state()))
        assertEquals(CourtFailure.ENVOY_REQUIRED, rejection(state(withEnvoy = false)))
        assertEquals(CourtFailure.ENVOY_REQUIRED, rejection(state(arrived = false)))
        assertEquals(CourtFailure.NOT_RULER, rejection(state(ruler = false)))
        assertEquals(CourtFailure.TARGET_UNAVAILABLE, rejection(state(), """{"targetNationId":1}"""))
        assertEquals(CourtFailure.TARGET_UNAVAILABLE, rejection(state(), """{"targetNationId":99}"""))
        assertEquals(CourtFailure.INVALID_INPUT, rejection(state(), """{"targetNationId":"2"}"""))
    }

    @Test fun `war and pending declaration reject while non aggression follows provisional policy`() {
        for (relation in listOf(DiplomacyState.WAR, DiplomacyState.DECLARATION))
            assertEquals(CourtFailure.ALREADY_AT_WAR, rejection(state(relation)))
        val assessment = CourtRules.assess(1, DiplomacyInput.DECLARE_WAR, args, state(DiplomacyState.NON_AGGRESSION))
        if (DeclareWarPolicy.ALLOW_DURING_NON_AGGRESSION) assertIs<CourtAssessment.Eligible>(assessment)
        else assertEquals(CourtFailure.AGREEMENT_UNAVAILABLE, assertIs<CourtAssessment.Rejected>(assessment).reason)
    }
}
