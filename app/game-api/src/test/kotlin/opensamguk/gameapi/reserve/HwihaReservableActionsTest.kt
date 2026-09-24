package opensamguk.gameapi.reserve

import kotlin.test.Test
import kotlin.test.assertTrue
import opensamguk.logic.input.HwihaPoliticalInput
import opensamguk.logic.input.HwihaTransferInput

class HwihaReservableActionsTest {
    @Test fun `all delivered political and transfer actions enter the HWIHA reserve allowlist`() {
        for (id in HwihaPoliticalInput.INPUT_IDS + HwihaTransferInput.INPUT_IDS)
            assertTrue(id in CommandReserveService.HWIHA_RESERVABLE_ACTIONS, id)
    }
}
