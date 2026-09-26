package opensamguk.logic.vassal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class VassalFoundingTest {
    private val rules = VassalRules.loadClasspath()
    private val issuer = VassalLord(1, 7, true, true)
    private val candidate = VassalFoundingCandidate(2, 7, 1, false, true)
    private val proposed = VassalContract(
        "contract-1", 1, 2, 7, setOf(10), rules.defaultTributePercent,
        rules.defaultReinforcementTroops, setOf(VassalAutonomy.COUNTY_POLICY),
        VassalDiplomacyRight.WITH_APPROVAL, VassalBreachKind.entries.toSet(), 60, 12,
    )

    @Test
    fun `human direct retinue founding needs second consent and returns atomic plan`() {
        assertEquals(VassalFoundingAssessment.Allowed(true),
            VassalFounding.assess(proposed, issuer, candidate, mapOf(10 to 7), emptyList(), rules, 12))
        assertFailsWith<IllegalArgumentException> {
            VassalFounding.plan(proposed, issuer, candidate, mapOf(10 to 7), emptyList(), rules, 12, false)
        }
        assertEquals(VassalFoundingPlan(1, 2, proposed),
            VassalFounding.plan(proposed, issuer, candidate, mapOf(10 to 7), emptyList(), rules, 12, true))
        assertEquals(VassalFoundingAssessment.Allowed(false),
            VassalFounding.assess(proposed, issuer, candidate.copy(isHuman = false), mapOf(10 to 7), emptyList(), rules, 12))
    }

    @Test
    fun `foreign candidate, nonretinue and overlapping fief are denied`() {
        assertEquals(VassalFoundingAssessment.Denied(VassalFoundingFailure.CANDIDATE_OUTSIDE_NATION),
            VassalFounding.assess(proposed, issuer, candidate.copy(nationId = 8), mapOf(10 to 7), emptyList(), rules, 12))
        assertEquals(VassalFoundingAssessment.Denied(VassalFoundingFailure.CANDIDATE_UNAVAILABLE),
            VassalFounding.assess(proposed, issuer, candidate.copy(isLiving = false), mapOf(10 to 7), emptyList(), rules, 12))
        assertEquals(VassalFoundingAssessment.Denied(VassalFoundingFailure.CANDIDATE_NOT_DIRECT_RETINUE),
            VassalFounding.assess(proposed, issuer, candidate.copy(retinueOwnerId = 3), mapOf(10 to 7), emptyList(), rules, 12))
        val earlier = proposed.copy(id = "earlier", vassalLordId = 3, signedTurn = 5)
        assertEquals(VassalFoundingAssessment.Denied(VassalFoundingFailure.FIEF_ALREADY_GRANTED),
            VassalFounding.assess(proposed, issuer, candidate, mapOf(10 to 7), listOf(earlier), rules, 12))
        assertEquals(VassalFoundingAssessment.Denied(VassalFoundingFailure.CONTRACT_ID_USED),
            VassalFounding.assess(proposed, issuer, candidate, mapOf(10 to 7), listOf(proposed.copy(endedTurn = 12)), rules, 12))
    }

    @Test
    fun `completion rechecks current fief ownership with the same denial as precheck`() {
        val ownership = mapOf(10 to 8)
        val precheck = assertIs<VassalFoundingAssessment.Denied>(
            VassalFounding.assess(proposed, issuer, candidate, ownership, emptyList(), rules, 12))
        val completion = assertIs<VassalFoundingCompletion.Denied>(
            VassalFounding.complete(proposed, issuer, candidate, ownership, emptyList(), rules, 12, true))
        assertEquals(precheck.reason, completion.reason)
    }

    @Test
    fun `human consent is explicit before founding and npc needs no consent`() {
        assertEquals(VassalFoundingCompletion.Denied(VassalFoundingFailure.CANDIDATE_CONSENT_PENDING),
            VassalFounding.complete(proposed, issuer, candidate, mapOf(10 to 7), emptyList(), rules, 12, null))
        assertEquals(VassalFoundingCompletion.Denied(VassalFoundingFailure.CANDIDATE_REFUSED),
            VassalFounding.complete(proposed, issuer, candidate, mapOf(10 to 7), emptyList(), rules, 12, false))
        assertEquals(VassalFoundingCompletion.Founded(VassalFoundingPlan(1, 2, proposed)),
            VassalFounding.complete(proposed, issuer, candidate, mapOf(10 to 7), emptyList(), rules, 12, true))
        assertEquals(VassalFoundingCompletion.Founded(VassalFoundingPlan(1, 2, proposed)),
            VassalFounding.complete(proposed, issuer, candidate.copy(isHuman = false), mapOf(10 to 7),
                emptyList(), rules, 12, null))
    }
}
