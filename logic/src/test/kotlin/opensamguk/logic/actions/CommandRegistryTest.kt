package opensamguk.logic.actions

import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * action-code → definition resolution + the 휴식 fallback.
 *   - resolve("che_농지개간") → CommerceInvestment (agri; key/name pin the agri parameterization)
 *   - resolve("che_상업투자") → CommerceInvestment (comm)
 *   - resolve("불명") (unknown) and resolve("휴식") → RestAction
 *   - fallback === RestAction (identity)
 */
class CommandRegistryTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `resolve che_농지개간 is a CommerceInvestment parameterized for agri`() {
        val def = registry.resolve("che_농지개간")
        assertTrue(def is CommerceInvestment, "che_농지개간 → CommerceInvestment")
        // key/name unambiguously pin the agri parameterization (cityKey="agri" ↔ name "농지 개간")
        assertEquals("che_농지개간", def.key)
        assertEquals("농지 개간", def.name)
    }

    @Test
    fun `resolve che_상업투자 is a CommerceInvestment parameterized for comm`() {
        val def = registry.resolve("che_상업투자")
        assertTrue(def is CommerceInvestment, "che_상업투자 → CommerceInvestment")
        assertEquals("che_상업투자", def.key)
        assertEquals("상업 투자", def.name)
    }

    @Test
    fun `unknown action-code falls back to RestAction`() {
        assertSame(RestAction, registry.resolve("불명"))
    }

    @Test
    fun `휴식 resolves to RestAction`() {
        assertSame(RestAction, registry.resolve("휴식"))
    }

    @Test
    fun `fallback is RestAction (identity)`() {
        assertSame(RestAction, registry.fallback)
    }
}
