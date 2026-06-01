package opensamguk.logic.event

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Unit tests for [BettingActions] — the betting family registrar (P6 step 1).
 *
 * Pins the betting-crash fix: the seeded `OpenNationBetting` / `FinishNationBetting` action names
 * must resolve through the factory (else [EventActionFactory.create] throws at monthly dispatch).
 */
class BettingActionsTest {

    @Test
    fun `registers both betting leaves by name`() {
        val factory = BettingActions.register(EventActionFactory())
        assertTrue(factory.has("OpenNationBetting"), "OpenNationBetting must be registered")
        assertTrue(factory.has("FinishNationBetting"), "FinishNationBetting must be registered")
    }

    @Test
    fun `factory builds the betting leaves from raw actions`() {
        val factory = BettingActions.register(EventActionFactory())
        val open = factory.create(RawAction("OpenNationBetting", emptyList()))
        assertTrue(open is OpenNationBettingAction, "OpenNationBetting builder yields OpenNationBettingAction")
        val finish = factory.create(
            RawAction("FinishNationBetting", listOf(JsonPrimitive(42))),
        )
        assertTrue(finish is FinishNationBettingAction, "FinishNationBetting builder yields FinishNationBettingAction")
    }
}
