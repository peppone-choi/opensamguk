package opensamguk.logic.input

import opensamguk.logic.domestic.ActivePlacement
import opensamguk.logic.domestic.DomesticCard
import opensamguk.logic.domestic.DomesticDiplomacy
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.domestic.PlacementOrder
import opensamguk.logic.domestic.PlacementPost
import opensamguk.logic.domestic.PlacementState
import opensamguk.logic.domestic.PlacementTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CourtOfferPeaceRulesTest {
    private val phase = Phase(200, 1, 1)
    private val args = """{"targetNationId":2}"""

    private fun person(id: Int, nationId: Int, ruler: Boolean, meta: Map<String, Any?> = emptyMap()) =
        DomesticPerson(id, "G$id", nationId, true, 2, if (ruler) 12 else 1,
            70, 70, 70, 70, 70, null, false, meta)

    private fun state(forward: Int = 0, withEnvoy: Boolean = true, ruler: Boolean = true): DomesticProjection {
        val order = PlacementOrder("envoy-1", 1, 5, PlacementPost.ENVOY, PlacementTarget.Nation(2), phase)
        val envoyMeta = PlacementState(ActivePlacement(order, phase, phase), null).toMetaValue()
        return DomesticProjection(
            RuleProfile.HWIHA, phase,
            listOf(person(1, 1, ruler, mapOf(LordStatus.META_KEY to ruler)),
                person(2, 1, false, mapOf(PlacementState.META_KEY to envoyMeta))),
            if (withEnvoy) listOf(DomesticCard(5, 1, 2, "TEST")) else emptyList(),
            emptyList(), listOf(DomesticNation(1, "아국", null, emptyMap()),
                DomesticNation(2, "상대국", null, emptyMap())), emptySet(),
            diplomacy = listOf(DomesticDiplomacy(1, 2, forward, 0)),
        )
    }

    @Test fun `peace proposal requires ruler envoy and bilateral war without changing another court input`() {
        assertIs<CourtAssessment.Eligible>(CourtRules.assess(1, DiplomacyInput.OFFER_PEACE, args, state()))
        assertEquals(CourtFailure.ENVOY_REQUIRED,
            (CourtRules.assess(1, DiplomacyInput.OFFER_PEACE, args, state(withEnvoy = false)) as CourtAssessment.Rejected).reason)
        assertEquals(CourtFailure.NOT_RULER,
            (CourtRules.assess(1, DiplomacyInput.OFFER_PEACE, args, state(ruler = false)) as CourtAssessment.Rejected).reason)
        assertEquals(CourtFailure.INVALID_INPUT,
            (CourtRules.assess(1, DiplomacyInput.OFFER_PEACE, """{"targetNationId":"2"}""", state()) as CourtAssessment.Rejected).reason)
        assertEquals(CourtFailure.NOT_AT_WAR,
            (CourtRules.assess(1, DiplomacyInput.OFFER_PEACE, args, state(forward = 2)) as CourtAssessment.Rejected).reason)
    }
}
