package opensamguk.engine.turn

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import opensamguk.logic.input.RuleProfile

class TurnWorldStateRuleProfileTest {
    private fun state(config: Map<String, Any?>, meta: Map<String, Any?> = emptyMap()) =
        TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 60,
            lastTurnTime = Instant.EPOCH, config = config, meta = meta)

    @Test fun `current world uses the temporary input profile`() {
        assertEquals(RuleProfile.HWIHA,
            state(mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN")).ruleProfile)
    }

    @Test fun `direct in-memory fixtures retain their profile projection`() {
        assertEquals(RuleProfile.SAMMO, state(emptyMap()).ruleProfile)
        assertEquals(RuleProfile.HWIHA, state(mapOf("ruleProfile" to "HWIHA")).ruleProfile)
        assertFailsWith<IllegalArgumentException> {
            state(mapOf("worldFormat" to "SAMMO")).ruleProfile
        }
    }
}
