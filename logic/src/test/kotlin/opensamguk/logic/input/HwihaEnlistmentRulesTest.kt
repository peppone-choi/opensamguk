package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HwihaEnlistmentRulesTest {
    private fun state() = EnlistmentSnapshot(
        RuleProfile.HWIHA,
        listOf(
            EnlistmentGeneral(1, 0, false, true),
            EnlistmentGeneral(2, 0, false, false),
            EnlistmentGeneral(10, 7, true, false),
            EnlistmentGeneral(11, 7, false, true),
            EnlistmentGeneral(20, 8, true, true),
        ),
        listOf(EnlistmentBond(1, 2), EnlistmentBond(10, 11)),
        mapOf(8 to 20, 7 to 10), setOf(10, 20), mapOf(10 to 1, 20 to 2), 1,
    )
    private val nation = EnlistmentRequest(1, EnlistmentMode.NATION, 7)
    private fun choices(request: EnlistmentRequest = nation, snapshot: EnlistmentSnapshot = state()) =
        (HwihaEnlistmentRules.assess(request, snapshot) as EnlistmentAssessment.Eligible).choices
    private fun denied(reason: EnlistmentFailure, snapshot: EnlistmentSnapshot, request: EnlistmentRequest = nation) {
        assertEquals(EnlistmentAssessment.Rejected(reason), HwihaEnlistmentRules.assess(request, snapshot))
    }

    @Test fun `NPC lord accepts a human with personal retinue at the price of one card`() {
        val before = state()
        val plan = choices(snapshot = before).single()
        assertEquals(EnlistmentPlan(1, 10, 7, listOf(1, 2), false, 1), plan)
        assertEquals(state(), before)
    }

    @Test fun `three direct variants preserve target semantics and random order is stable`() {
        assertEquals(choices(), choices(EnlistmentRequest(1, EnlistmentMode.GENERAL, 11)))
        val random = EnlistmentRequest(1, EnlistmentMode.RANDOM)
        val expected = choices(random)
        assertEquals(listOf(7, 8), expected.map { it.nationId })
        assertEquals(expected, choices(random, state().copy(generals = state().generals.reversed(), bonds = state().bonds.reversed())))
        assertEquals(expected[1], HwihaEnlistmentRules.select(EnlistmentAssessment.Eligible(expected)) { size ->
            assertEquals(2, size); 1
        })
        assertEquals(choices().single(), HwihaEnlistmentRules.select(EnlistmentAssessment.Eligible(choices())) { error("no draw") })
    }

    @Test fun `fresh execution recheck rejects removed lord permission and spent renown`() {
        assertTrue(HwihaEnlistmentRules.assess(nation, state()) is EnlistmentAssessment.Eligible)
        denied(EnlistmentFailure.TARGET_NOT_ACCEPTING, state().copy(acceptingLordIds = setOf(20)))
        denied(EnlistmentFailure.INSUFFICIENT_RENOWN, state().copy(freeRenownByLord = mapOf(10 to 0)))
        denied(EnlistmentFailure.TARGET_NOT_LORD, state().copy(generals = state().generals.map { when (it.id) { 10 -> it.copy(isLord = false); 11 -> it.copy(isHuman = false); else -> it } }))
    }

    @Test fun `random excludes closed and full nations and reports no candidate`() {
        val request = EnlistmentRequest(1, EnlistmentMode.RANDOM)
        assertEquals(listOf(20), choices(request, state().copy(freeRenownByLord = mapOf(10 to 0, 20 to 2))).map { it.masterId })
        denied(EnlistmentFailure.NO_ELIGIBLE_NATION, state().copy(acceptingLordIds = emptySet()), request)
    }

    @Test fun `wandering lord loses status but cannot silently keep human subordinates`() {
        val wandering = state().copy(generals = state().generals.map { if (it.id == 1) it.copy(isLord = true) else it })
        assertTrue(choices(snapshot = wandering).single().relinquishLordStatus)
        denied(EnlistmentFailure.HUMAN_RETAINER_REQUIRES_LORD, wandering.copy(
            generals = wandering.generals.map { if (it.id == 2) it.copy(isHuman = true) else it },
        ))
    }

    @Test fun `already serving or bound cannot be applied twice`() {
        denied(EnlistmentFailure.ALREADY_SERVING, state().copy(generals = state().generals.map { if (it.id in setOf(1, 2)) it.copy(nationId = 7) else it }))
        denied(EnlistmentFailure.ALREADY_BOUND, state().copy(
            generals = state().generals + EnlistmentGeneral(30, 0, true, false),
            bonds = state().bonds + EnlistmentBond(30, 1),
        ))
    }

    @Test fun `cycles double owners missing members and mixed affiliation are rejected`() {
        for (bonds in listOf(
            state().bonds + EnlistmentBond(20, 2),
            state().bonds + EnlistmentBond(2, 99),
            listOf(EnlistmentBond(10, 11), EnlistmentBond(11, 10)),
        )) denied(EnlistmentFailure.INVALID_RETINUE, state().copy(bonds = bonds))
        denied(EnlistmentFailure.INVALID_RETINUE, state().copy(generals = state().generals.map { if (it.id == 2) it.copy(nationId = 7) else it }))
    }

    @Test fun `target affiliation must also be consistent`() {
        denied(EnlistmentFailure.INVALID_RETINUE, state().copy(
            generals = state().generals.map { if (it.id == 11) it.copy(nationId = 8) else it },
        ), EnlistmentRequest(1, EnlistmentMode.GENERAL, 11))
    }

    @Test fun `nested human ownership must also be consistent`() {
        denied(EnlistmentFailure.INVALID_RETINUE, state().copy(
            generals = state().generals + EnlistmentGeneral(99, 0, false, true),
            bonds = state().bonds + EnlistmentBond(2, 99),
        ))
    }

    @Test fun `profile request target and explicit sovereign are checked`() {
        denied(EnlistmentFailure.WRONG_RULE_PROFILE, state().copy(profile = RuleProfile.SAMMO))
        denied(EnlistmentFailure.INVALID_REQUEST, state(), nation.copy(targetId = null))
        denied(EnlistmentFailure.INVALID_REQUEST, state(), EnlistmentRequest(1, EnlistmentMode.RANDOM, 7))
        denied(EnlistmentFailure.ACTOR_NOT_FOUND, state(), nation.copy(actorId = 99))
        denied(EnlistmentFailure.TARGET_NOT_FOUND, state(), nation.copy(targetId = 99))
        denied(EnlistmentFailure.TARGET_NOT_LORD, state().copy(sovereignByNation = mapOf(7 to 20)))
        denied(EnlistmentFailure.TARGET_NOT_LORD, state(), EnlistmentRequest(1, EnlistmentMode.GENERAL, 2))
        assertFailsWith<IllegalArgumentException> { choices(snapshot = state().copy(actorCardCost = 0)) }
    }
}
