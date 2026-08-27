package opensamguk.gameapi.web

import opensamguk.gameapi.precheck.PrecheckResult
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandCatalogRowFactoryTest {
    private val registry = CommandRegistry(GeneralActionPipeline())

    @Test
    fun `compound form is emitted without scalar arg guessing`() {
        val row = CommandCatalogRowFactory.create(
            registry.resolve("che_포상"),
            PrecheckResult.Available,
            "인사",
            "NATION_TURN",
        )

        assertTrue(row.reqArg)
        assertNull(row.argType)
        assertEquals(listOf("isGold", "amount", "destGeneralID"), row.form?.fields?.map { it.name })
        assertEquals(listOf("toggle", "amount", "select"), row.form?.fields?.map { it.control })
    }

    @Test
    fun `unknown precheck remains selectable only when a form can collect missing inputs`() {
        val formRow = CommandCatalogRowFactory.create(
            registry.resolve("che_불가침제의"),
            PrecheckResult.Unknown(emptyList()),
            "외교",
            "NATION_TURN",
        )
        val noArgRow = CommandCatalogRowFactory.create(
            registry.resolve("che_농지개간"),
            PrecheckResult.Unknown(emptyList()),
            "내정",
            "GENERAL_TURN",
        )

        assertTrue(formRow.possible)
        assertNull(formRow.reason)
        assertFalse(noArgRow.possible)
        assertEquals("정보 부족", noArgRow.reason)
    }
}
