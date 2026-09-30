package opensamguk.gameapi.read

import java.time.Instant
import opensamguk.common.turn.TurnCatchUp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TurnLoopHealthTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")

    private fun world(last: Instant?, executed: Instant? = now.minusSeconds(10),
                      status: String = "OPEN", tickSeconds: Int = 3600,
                      catchUp: Map<String, Any?>? = null) = WorldStateReadEntity(
        status = status, tickSeconds = tickSeconds,
        meta = buildMap {
            last?.let { put("lastTurnTime", it.toString()) }
            executed?.let { put("lastTickExecutedAt", it.toString()) }
        },
        catchUp = catchUp,
    )

    @Test
    fun `recent running turn exposes both timestamps`() {
        val last = now.minusSeconds(1800)
        val observed = TurnLoopHealth.observe(world(last), now)
        assertEquals(TurnLoopHealth.State.RUNNING, observed.state)
        assertEquals(last.toString(), observed.lastTurnAt)
        assertEquals(last.plusSeconds(3600).toString(), observed.nextTurnAt)
        assertEquals(now.minusSeconds(10).toString(), observed.lastTickExecutedAt)
        assertEquals(10L, observed.staleSeconds)
        assertFalse(observed.stale)
    }

    @Test
    fun `twenty five hour stop cannot be healthy even with a long turn cadence`() {
        val observed = TurnLoopHealth.observe(world(now.minusSeconds(3600),
            executed = now.minusSeconds(25 * 3600 + 1), tickSeconds = 12 * 3600), now)
        assertEquals(TurnLoopHealth.State.STALLED, observed.state)
        assertTrue(observed.stale)
    }

    @Test
    fun `missing clock is stalled while an intentional pause is not`() {
        assertEquals(TurnLoopHealth.State.STALLED,
                     TurnLoopHealth.observe(world(now.minusSeconds(7200), executed = null), now).state)
        val paused = TurnLoopHealth.observe(world(now.minusSeconds(30 * 3600), status = "PRE_OPEN"), now)
        assertEquals(TurnLoopHealth.State.PAUSED, paused.state)
        assertFalse(paused.stale)
    }

    @Test
    fun `catch up does not hide a stopped loop`() {
        val last = now.minusSeconds(20 * 3600 + 300)
        for (multiplier in listOf(2, 4)) {
            val catchUp = TurnCatchUp.start(last.plusSeconds(300), now).copy(multiplier = multiplier).toMeta()
            assertEquals(TurnLoopHealth.State.CATCHING_UP,
                         TurnLoopHealth.observe(world(last, tickSeconds = 300, catchUp = catchUp), now).state)
            assertEquals(TurnLoopHealth.State.STALLED,
                         TurnLoopHealth.observe(world(last, executed = now.minusSeconds(901),
                                                      tickSeconds = 300, catchUp = catchUp), now).state)
        }
    }

    @Test
    fun `future clock beyond allowed skew is invalid for health`() {
        assertEquals(TurnLoopHealth.State.STALLED,
                     TurnLoopHealth.observe(world(now, executed = now.plusSeconds(301)), now).state)
    }

    @Test
    fun `world before its first scheduled turn is waiting`() {
        val waiting = world(null, executed = null).apply { startTime = now.plusSeconds(3600) }
        assertEquals(TurnLoopHealth.State.WAITING, TurnLoopHealth.observe(waiting, now).state)
        waiting.status = "PRE_OPEN"
        assertEquals(TurnLoopHealth.State.PAUSED, TurnLoopHealth.observe(waiting, now).state)
    }

    @Test
    fun `paused and stalled schedules are null without deleting the game clock`() {
        for (sample in listOf(world(now.minusSeconds(60), status = "PRE_OPEN"),
            world(now.minusSeconds(26 * 3600L), executed = now.minusSeconds(26 * 3600L)))) {
            val result = TurnLoopHealth.observe(sample, now)
            assertEquals(null, result.nextTurnAt)
            assertEquals(sample.meta["lastTurnTime"], result.lastTurnAt)
            assertEquals(false, result.healthy)
        }
    }

}
