package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AiPolicyRegistryTest {
    private val catalog = InputCatalog.load()

    @Test
    fun `every policy is explicitly bound to a selector or unused reason`() {
        assertEquals(catalog.entries.size, AiPolicyRegistry.bindings.size)
        AiPolicyRegistry.validate(catalog)
        assertTrue(AiPolicyRegistry.bindings.values.filterIsInstance<AiPolicyBinding.Unused>()
            .all { it.reason.isNotBlank() })
        assertEquals(AiPolicyBinding.Selector(AiSelectorKey.COURT_DISPATCH),
            AiPolicyRegistry.bindings[catalog["court.dispatch"]!!.aiPolicyId])
        assertIs<AiPolicyBinding.Unused>(AiPolicyRegistry.bindings[catalog["court.reward"]!!.aiPolicyId])
    }

    @Test
    fun `new unregistered or duplicated policy fails startup validation`() {
        val original = catalog.entries.first()
        assertFailsWith<IllegalArgumentException> {
            AiPolicyRegistry.validate(InputCatalog(catalog.entries + original.copy(
                inputId = "action.new", aiPolicyId = "ai.action.new")))
        }
        assertFailsWith<IllegalArgumentException> {
            AiPolicyRegistry.validate(InputCatalog(catalog.entries + original.copy(inputId = "action.new")))
        }
    }

    @Test
    fun `undelivered and wrong selector policies cannot choose an NPC input`() {
        assertTrue(AiPolicyRegistry.selectable(catalog, "action.deploy", AiSelectorKey.DEPLOY))
        assertFalse(AiPolicyRegistry.selectable(catalog, "action.deploy", AiSelectorKey.PERSONAL))
        assertFalse(AiPolicyRegistry.selectable(catalog, "action.retire", AiSelectorKey.PERSONAL))
        assertFalse(AiPolicyRegistry.selectable(catalog, "action.unknown", AiSelectorKey.DEPLOY))
    }
}
