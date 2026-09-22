package opensamguk.logic.input

import kotlin.test.*

class HwihaEnlistmentPrecheckTest {
    private fun person(id: Int, nation: Int = 0, lord: Boolean = false, capacity: Int = 30,
                       npc: Int = 2, user: String? = null) = EnlistmentPersonRow(
        PersonPolicyInput(id, nation, 70, 70, 70, 70, 70, mapOf("hwihaLord" to lord,
            HwihaPersonPolicyState.META_KEY to HwihaPersonPolicyState(capacity, true, "fixture", "v1", id).toMetaValue())),
        "G$id", if (lord && nation > 0) 12 else 0, npc, user)
    private fun state() = HwihaEnlistmentProjection(RuleProfile.HWIHA,
        listOf(person(1), person(10, 1, true), person(20, 2, true)), emptyList(), setOf(1, 2))
    private val request = EnlistmentRequest(1, EnlistmentMode.NATION, 1)
    private fun reason(state: HwihaEnlistmentProjection, request: EnlistmentRequest = this.request) =
        assertIs<EnlistmentAssessment.Rejected>(HwihaEnlistmentPrecheck.assess(request, state)).reason

    @Test fun `current shared projection selects actual unique sovereign and preserves direct mode semantics`() {
        val state = state()
        val plan = assertIs<EnlistmentAssessment.Eligible>(HwihaEnlistmentPrecheck.assess(request, state)).choices.single()
        assertEquals(10, plan.masterId)
        assertEquals(7, plan.masterRenownCost)
        assertEquals(EnlistmentFailure.TARGET_NOT_FOUND,
            reason(state.copy(persons = state.persons + person(11, 1, true))))
        assertEquals(EnlistmentFailure.TARGET_NOT_FOUND,
            reason(state.copy(nationIds = setOf(2)), EnlistmentRequest(1, EnlistmentMode.GENERAL, 10)))
    }

    @Test fun `missing target policy is distinct from real capacity shortage and random excludes only that lord`() {
        val base = state()
        val missing = base.copy(persons = base.persons.map { if (it.policy.id == 10)
            it.copy(policy = it.policy.copy(meta = mapOf("hwihaLord" to true))) else it })
        assertEquals(EnlistmentFailure.POLICY_UNAVAILABLE, reason(missing))
        val random = assertIs<EnlistmentAssessment.Eligible>(HwihaEnlistmentPrecheck.assess(
            EnlistmentRequest(1, EnlistmentMode.RANDOM), missing))
        assertEquals(listOf(20), random.choices.map { it.masterId })
        assertEquals(EnlistmentFailure.INSUFFICIENT_RENOWN,
            reason(base.copy(persons = listOf(person(1), person(10, 1, true, capacity = 6)))))
    }

    @Test fun `user owned NPC descendant counts as human and cannot follow a former lord`() {
        val state = state().copy(persons = listOf(person(1, lord = true), person(2, user = "42"), person(10, 1, true)),
            cards = listOf(EnlistmentCardRow(1, 1, 2, "G2")))
        assertEquals(EnlistmentFailure.HUMAN_RETAINER_REQUIRES_LORD, reason(state))
    }

    @Test fun `politics and charm affect capacity and corrupt projections never become eligible`() {
        val base = state().copy(persons = listOf(person(1).let {
            it.copy(policy = it.policy.copy(leadership = 20, strength = 20, intelligence = 20, politics = 100, charm = 100))
        }, person(10, 1, true, capacity = 5)))
        assertEquals(EnlistmentFailure.INSUFFICIENT_RENOWN, reason(base))
        assertEquals(EnlistmentFailure.INVALID_RETINUE, reason(base.copy(persons = base.persons + base.persons.first())))
        assertEquals(EnlistmentFailure.WRONG_RULE_PROFILE, reason(base.copy(profile = RuleProfile.SAMMO)))
        assertEquals(EnlistmentFailure.INVALID_REQUEST, reason(base, request.copy(targetId = 0)))
    }
}
