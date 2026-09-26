package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorldRuleProfileTest {
    private val current = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN")

    @Test fun `current world maps to the temporary input profile`() {
        assertEquals(RuleProfile.HWIHA, WorldRuleProfile.defaultProfile())
        assertEquals(RuleProfile.HWIHA, WorldRuleProfile.require(current))
    }

    @Test fun `projection retains direct test fixture profiles but rejects retired format markers`() {
        assertEquals(null, WorldRuleProfile.resolve(emptyMap()))
        assertEquals(null, WorldRuleProfile.resolve(mapOf("ruleProfile" to "unknown")))
        assertEquals(null, WorldRuleProfile.resolve(mapOf("ruleProfile" to 1)))
        assertFailsWith<IllegalArgumentException> { WorldRuleProfile.require(emptyMap()) }
        assertEquals(RuleProfile.HWIHA, WorldRuleProfile.require(mapOf("ruleProfile" to "HWIHA")))
        assertFailsWith<IllegalArgumentException> {
            WorldRuleProfile.require(mapOf("worldFormat" to "SAMMO"))
        }
    }

    @Test fun `rollback switch cannot restore old worlds`() {
        assertEquals(false, WorldRuleProfile.rollbackEnabled(null))
        assertEquals(false, WorldRuleProfile.rollbackEnabled("false"))
        assertFailsWith<IllegalArgumentException> { WorldRuleProfile.rollbackEnabled("true") }
        assertFailsWith<IllegalArgumentException> { WorldRuleProfile.require(current, rollback = true) }
    }
}
