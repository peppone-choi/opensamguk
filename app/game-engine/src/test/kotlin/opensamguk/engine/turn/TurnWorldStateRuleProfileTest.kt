package opensamguk.engine.turn

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import opensamguk.logic.input.RuleProfile

class TurnWorldStateRuleProfileTest {
    private fun state(config: Map<String, Any?>) =
        TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 60, lastTurnTime = Instant.EPOCH, config = config)

    @Test
    fun `worlds seeded before the field exist are SAMMO`() {
        assertEquals(RuleProfile.SAMMO, state(emptyMap()).ruleProfile)
    }

    @Test
    fun `hwiha worlds read the seeded value`() {
        assertEquals(RuleProfile.HWIHA, state(mapOf("ruleProfile" to "HWIHA")).ruleProfile)
    }

    @Test
    fun `unknown text fails instead of quietly becoming SAMMO`() {
        assertFailsWith<IllegalArgumentException> { state(mapOf("ruleProfile" to "hwiha")).ruleProfile }
    }

    @Test
    fun `non string config values fail instead of quietly becoming SAMMO`() {
        for (value in listOf(1, true, listOf("HWIHA"), mapOf("name" to "HWIHA"))) {
            assertFailsWith<IllegalArgumentException>("invalid ruleProfile: $value") {
                state(mapOf("ruleProfile" to value)).ruleProfile
            }
        }
    }

}
