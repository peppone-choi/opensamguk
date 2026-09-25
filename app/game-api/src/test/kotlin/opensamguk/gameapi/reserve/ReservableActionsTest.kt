package opensamguk.gameapi.reserve

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import opensamguk.logic.input.EnlistmentInput
import opensamguk.logic.input.PoliticalInput
import opensamguk.logic.input.TransferInput

class HwihaReservableActionsTest {
    @Test fun `political and transfer action ids enter the HWIHA reserve allowlist regardless of delivery state`() {
        for (id in PoliticalInput.INPUT_IDS + TransferInput.INPUT_IDS)
            assertTrue(id in CommandReserveService.HWIHA_RESERVABLE_ACTIONS, id)
    }

    @Test fun `unregistered enlistment aliases stay outside the reserve allowlist`() {
        assertTrue("action.enlist" in CommandReserveService.HWIHA_RESERVABLE_ACTIONS)
        for (id in setOf(EnlistmentInput.RANDOM, EnlistmentInput.TARGET))
            assertFalse(id in CommandReserveService.HWIHA_RESERVABLE_ACTIONS, id)
    }
}
