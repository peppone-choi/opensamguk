package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DirectEnlistmentHierarchyTest {
    @Test fun `GENERAL enters the selected ordinary general's division without replacing it with the ruler`() {
        val snapshot = EnlistmentSnapshot(RuleProfile.HWIHA,
            listOf(EnlistmentGeneral(1, 0, false, true), EnlistmentGeneral(10, 7, true, false),
                EnlistmentGeneral(11, 7, false, true)),
            listOf(EnlistmentBond(10, 11)), mapOf(7 to 10), setOf(10, 11), mapOf(10 to 1, 11 to 8), 1)
        val plan = assertIs<EnlistmentAssessment.Eligible>(EnlistmentRules.assess(
            EnlistmentRequest(1, EnlistmentMode.GENERAL, 11), snapshot)).choices.single()
        assertEquals(11, plan.masterId)
        assertEquals(7, plan.nationId)
        assertEquals(listOf(1), plan.joiningGeneralIds)
    }

    @Test fun `joining a division preserves an existing human and NPC subtree on its own budgets`() {
        val snapshot = EnlistmentSnapshot(RuleProfile.HWIHA,
            listOf(EnlistmentGeneral(1, 0, false, true), EnlistmentGeneral(2, 0, false, true),
                EnlistmentGeneral(3, 0, false, false), EnlistmentGeneral(10, 7, true, false),
                EnlistmentGeneral(11, 7, false, true)),
            listOf(EnlistmentBond(1, 2), EnlistmentBond(2, 3), EnlistmentBond(10, 11)),
            mapOf(7 to 10), setOf(10, 11), mapOf(10 to 1, 11 to 8), 1)
        val plan = assertIs<EnlistmentAssessment.Eligible>(EnlistmentRules.assess(
            EnlistmentRequest(1, EnlistmentMode.GENERAL, 11), snapshot)).choices.single()
        assertEquals(11, plan.masterId)
        assertEquals(listOf(1, 2, 3), plan.joiningGeneralIds)
        assertEquals(1, plan.masterRenownCost)
        assertEquals(3, snapshot.bonds.size)
    }

    private fun person(id: Int, nation: Int, lord: Boolean = false, cap: Int = 30, accepts: Boolean = true) =
        EnlistmentPersonRow(PersonPolicyInput(id, nation, 60, 60, 60, 60, 60,
            mapOf("lord" to lord, PersonPolicyState.META_KEY to
                PersonPolicyState(cap, accepts, "synthetic-qa:hierarchy", "v1", id).toMetaValue())),
            "G$id", if (lord) 12 else 0, if (id == 1 || id == 11) 0 else 2, null)
    private fun projection(capacity: Int = 12, accepts: Boolean = true) = EnlistmentProjection(RuleProfile.HWIHA,
        listOf(person(1, 0), person(2, 0), person(10, 7, true, 6), person(11, 7, cap = capacity, accepts = accepts),
            person(13, 7)),
        listOf(EnlistmentCardRow(1, 10, 11, "G11"), EnlistmentCardRow(2, 11, 13, "G13"),
            EnlistmentCardRow(3, 1, 2, "G2")), setOf(7))
    private val direct = EnlistmentRequest(1, EnlistmentMode.GENERAL, 11)

    @Test fun `actual shared policy charges the selected division only even when its ruler has no free capacity`() {
        val state = projection()
        val budget = assertIs<RenownBudgetResult.Ready>(EnlistmentBudget.assess(1, state.profile,
            state.persons.map { it.policy }, state.cards.map { DirectPersonCard(it.id, it.masterId, it.generalId) }))
        assertEquals(0, budget.freeRenownByLord[10])
        assertEquals(6, budget.freeRenownByOwner[11])
        assertTrue(11 in budget.acceptingOwnerIds)
        val plan = assertIs<EnlistmentAssessment.Eligible>(EnlistmentPrecheck.assess(direct, state)).choices.single()
        assertEquals(11, plan.masterId)
        assertEquals(6, plan.masterRenownCost)
        assertEquals(listOf(1, 2), plan.joiningGeneralIds)
    }

    @Test fun `ordinary superior consent policy and direct budget cannot fall back to the ruler`() {
        assertEquals(EnlistmentFailure.INSUFFICIENT_RENOWN,
            assertIs<EnlistmentAssessment.Rejected>(EnlistmentPrecheck.assess(direct, projection(11))).reason)
        assertEquals(EnlistmentFailure.TARGET_NOT_ACCEPTING,
            assertIs<EnlistmentAssessment.Rejected>(EnlistmentPrecheck.assess(direct, projection(accepts = false))).reason)
        val state = projection()
        val missingPolicy = state.copy(persons = state.persons.map { if (it.policy.id == 11)
            it.copy(policy = it.policy.copy(meta = mapOf("lord" to false))) else it })
        assertEquals(EnlistmentFailure.POLICY_UNAVAILABLE,
            assertIs<EnlistmentAssessment.Rejected>(EnlistmentPrecheck.assess(direct, missingPolicy)).reason)
    }

    @Test fun `ordinary superior with an empty division uses existing policy without a synthetic allowance`() {
        val state = projection().let { it.copy(cards = it.cards.filterNot { card -> card.masterId == 11 }) }
        val plan = assertIs<EnlistmentAssessment.Eligible>(EnlistmentPrecheck.assess(direct, state)).choices.single()
        assertEquals(11, plan.masterId)
        assertEquals(6, plan.masterRenownCost)
    }
}
