package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorldRuleProfileTest {
    @Test
    fun `fresh default is HWIHA and restored missing world needs rollback`() {
        assertEquals(RuleProfile.HWIHA, WorldRuleProfile.defaultProfile(rollback = false))
        assertNull(WorldRuleProfile.resolve(emptyMap(), rollback = false))
        assertFailsWith<IllegalArgumentException> { WorldRuleProfile.require(emptyMap(), rollback = false) }
        assertEquals(RuleProfile.SAMMO, RuleProfile.fromWorldConfig(null))
        assertEquals(RuleProfile.SAMMO, WorldRuleProfile.require(emptyMap(), rollback = true))
        assertEquals(RuleProfile.HWIHA, WorldRuleProfile.require(mapOf("ruleProfile" to "HWIHA"), rollback = true))
        assertEquals(RuleProfile.SAMMO, WorldRuleProfile.require(mapOf("ruleProfile" to "SAMMO"), rollback = false))
    }

    @Test
    fun `invalid saved profile fails closed`() {
        for (value in listOf(null, "other", 7)) {
            val config = mapOf("ruleProfile" to value)
            assertNull(WorldRuleProfile.resolve(config, rollback = false))
            assertFailsWith<IllegalArgumentException> { WorldRuleProfile.require(config, rollback = false) }
        }
    }

    @Test
    fun `rollback env accepts only a boolean`() {
        assertEquals(false, WorldRuleProfile.rollbackEnabled(null))
        assertEquals(false, WorldRuleProfile.rollbackEnabled("false"))
        assertEquals(true, WorldRuleProfile.rollbackEnabled("true"))
        assertFailsWith<IllegalArgumentException> { WorldRuleProfile.rollbackEnabled("yes") }
    }
}
