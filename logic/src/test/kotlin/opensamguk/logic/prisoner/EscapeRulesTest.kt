package opensamguk.logic.prisoner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EscapeRulesTest {
    private val fail = EscapeTerms(0, 100, 1, 1, 10, 0, 0)
    @Test fun `captivity permits exactly three input kinds and unresolved conditions block`() {
        val state = captured()
        for (input in CaptiveInput.entries) {
            assertEquals(if (input == CaptiveInput.OTHER) PrisonerCode.ACTION_NOT_ALLOWED else PrisonerCode.APPLIED,
                EscapeRules.input(state, 20, input, true).code)
            assertEquals(if (input == CaptiveInput.OTHER) PrisonerCode.ACTION_NOT_ALLOWED else PrisonerCode.NOT_DECIDED,
                EscapeRules.input(state, 20, input, null).code)
        }
    }

    @Test fun `failed escape preserves life raises guards difficulty and blocks early retry`() {
        val result = EscapeRules.attempt(captured(), "escape", 20, 5, 7, 1, fail, true)
        assertEquals(PrisonerCode.APPLIED, result.code)
        val general = result.state.generals.getValue(20)
        assertEquals(GeneralCaptivity.CAPTIVE, general.status)
        assertEquals(1L, general.guardLevel)
        assertEquals(1L, general.escapeDifficulty)
        assertEquals(15L, general.retryAvailableAt)
        assertEquals(1L, general.attempts)
        assertEquals(PrisonerCode.ALREADY_COMPLETED, EscapeRules.attempt(result.state, "escape", 20, 5, 7, 1, fail, true).code)
        assertEquals(PrisonerCode.RETRY_WAIT, EscapeRules.attempt(result.state, "retry", 20, 14, 7, 2, fail, true).code)
        assertEquals(PrisonerCode.INVALID_REQUEST, EscapeRules.attempt(result.state, "stale", 20, 15, 7, 2, fail, true).code)
        val retried = EscapeRules.attempt(result.state, "retry", 20, 15, 7, 2, fail.copy(quotedGuardLevel = 1, quotedDifficulty = 1), true)
        assertEquals(2L, retried.state.generals.getValue(20).guardLevel)
        assertEquals(2L, retried.state.generals.getValue(20).attempts)
    }

    @Test fun `each missing escape parameter leaves state unchanged`() {
        val state = captured()
        for (terms in listOf(EscapeTerms(), fail.copy(successParts = null), fail.copy(totalParts = null),
            fail.copy(guardIncrease = null), fail.copy(difficultyIncrease = null), fail.copy(retryTicks = null),
            fail.copy(quotedGuardLevel = null), fail.copy(quotedDifficulty = null))) {
            val result = EscapeRules.attempt(state, "escape", 20, 0, 7, 1, terms, true)
            assertEquals(PrisonerCode.NOT_DECIDED, result.code)
            assertEquals(state, result.state)
        }
    }

    @Test fun `successful escape changes captivity without automatic faction change`() {
        val result = EscapeRules.attempt(captured(), "escape", 20, 0, 7, 1, fail.copy(successParts = 100), true)
        assertEquals(GeneralCaptivity.ESCAPED, result.state.generals.getValue(20).status)
        assertEquals(2L, result.state.generals.getValue(20).nationId)
        assertEquals(PrisonerCode.ACTION_NOT_ALLOWED, EscapeRules.input(result.state, 20, CaptiveInput.OFFER_REPLY, true).code)
    }

    @Test fun `hash based trials are deterministic and validate ranges and time overflow`() {
        val state = captured()
        for (seed in 0L..20L) assertEquals(
            EscapeRules.attempt(state, "escape", 20, 0, seed, 1, fail.copy(successParts = 50), true),
            EscapeRules.attempt(state, "escape", 20, 0, seed, 1, fail.copy(successParts = 50), true))
        assertEquals(PrisonerCode.INVALID_REQUEST, EscapeRules.attempt(state, "overflow", 20, Long.MAX_VALUE, 7, 1, fail, true).code)
        assertFailsWith<IllegalArgumentException> { fail.copy(successParts = 101) }
        assertFailsWith<IllegalArgumentException> { fail.copy(retryTicks = 0) }
    }

    @Test fun `NPC captive input policy is not invented`() {
        val state = PrisonerRecords.captureGeneral(captured(), "npc", 21, 2, false, 10).state
        val result = EscapeRules.attempt(state, "escape", 21, 0, 7, 1, fail, true)
        assertEquals(PrisonerCode.NOT_DECIDED, result.code)
        assertEquals(state, result.state)
    }
}
