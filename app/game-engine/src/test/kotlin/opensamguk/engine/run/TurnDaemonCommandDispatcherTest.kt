package opensamguk.engine.run

import opensamguk.common.wire.AuctionBidFail
import opensamguk.common.wire.AuctionFinalizeOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [TurnDaemonCommandDispatcher] — the P6 command-routing seam.
 *
 * Verifies: implemented command types route to their handler (non-null result), control / not-yet-
 * built command types return null, and [TurnDaemonCommandDispatcher.dispatchAll] filters the nulls.
 */
class TurnDaemonCommandDispatcherTest {

    private val t0: Instant = Instant.parse("2025-01-01T00:00:00Z")

    private fun emptyWorld() =
        InMemoryTurnWorld(WorldSnapshot(state = TurnWorldState(1, 200, 1, 3600, t0)))

    @Test
    fun `routes AuctionBid to the auction bid handler`() {
        val dispatcher = TurnDaemonCommandDispatcher(emptyWorld())
        // empty world → no such general → the handler returns AuctionBidFail (routing proven).
        val result = dispatcher.dispatch(
            TurnDaemonCommand.AuctionBid(auctionId = 1, generalId = 999, amount = 100),
        )
        assertTrue(result is AuctionBidFail, "AuctionBid should route to AuctionBidHandler")
        assertEquals(1, result.auctionId)
    }

    @Test
    fun `routes AuctionFinalize to the finalize handler`() {
        val dispatcher = TurnDaemonCommandDispatcher(emptyWorld())
        // empty world + shell stub (hostGeneralId=0, no bids) → rollback → AuctionFinalizeOk.
        val result = dispatcher.dispatch(TurnDaemonCommand.AuctionFinalize(auctionId = 7))
        assertTrue(result is AuctionFinalizeOk, "AuctionFinalize should route to AuctionFinalizeHandler")
        assertEquals(7, result.auctionId)
    }

    @Test
    fun `control command has no engine handler and returns null`() {
        val dispatcher = TurnDaemonCommandDispatcher(emptyWorld())
        assertNull(dispatcher.dispatch(TurnDaemonCommand.Pause(reason = "test")))
        assertNull(dispatcher.dispatch(TurnDaemonCommand.Resume()))
    }

    @Test
    fun `dispatchAll keeps only the non-null results in order`() {
        val dispatcher = TurnDaemonCommandDispatcher(emptyWorld())
        val results = dispatcher.dispatchAll(
            listOf(
                TurnDaemonCommand.Pause(),
                TurnDaemonCommand.AuctionBid(auctionId = 2, generalId = 999, amount = 50),
                TurnDaemonCommand.Resume(),
                TurnDaemonCommand.AuctionFinalize(auctionId = 3),
            ),
        )
        // Pause + Resume → null (filtered); AuctionBid (fail) + AuctionFinalize (ok) → kept.
        assertEquals(2, results.size)
        assertTrue(results[0] is AuctionBidFail)
        assertTrue(results[1] is AuctionFinalizeOk)
    }
}
