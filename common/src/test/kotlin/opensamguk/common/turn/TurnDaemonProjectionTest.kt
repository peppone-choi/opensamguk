package opensamguk.common.turn

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnDaemonProjectionTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val budget = Duration.ofSeconds(30) // Test input, not a production policy.
    private fun source(paused: Boolean) = TurnDaemonObservation("pep", 1, now.minusSeconds(1), now, paused)
    private fun observe(
        observation: TurnDaemonObservation? = source(false),
        lastTick: Instant? = now.minusSeconds(1),
        nextTurn: Instant? = null,
        catchUp: Boolean = false,
        tickSeconds: Int = 60,
    ) = TurnDaemonProjection.observe("pep", 1, now, observation, budget, lastTick, nextTurn, tickSeconds, catchUp)

    @Test
    fun `current pause is authoritative despite old marker and reason remains unknown`() {
        val result = observe(source(true), now.minusSeconds(26 * 3600L), catchUp = true)
        assertEquals(TurnDaemonProjection.State.PAUSED, result.state)
        assertEquals(true, result.paused)
        assertEquals("UNKNOWN", result.pausedReason)
        assertEquals(now, result.serverTime)
    }

    @Test
    fun `current unpaused observation exposes stalled catch-up instead of hiding it`() {
        assertEquals(TurnDaemonProjection.State.STALLED, observe(lastTick = now.minusSeconds(181), catchUp = true).state)
        assertEquals(TurnDaemonProjection.State.CATCHING_UP, observe(catchUp = true).state)
        assertEquals(TurnDaemonProjection.State.RUNNING, observe().state)
    }

    @Test
    fun `future first turn waits but missing due marker stalls`() {
        assertEquals(TurnDaemonProjection.State.WAITING, observe(lastTick = null, nextTurn = now.plusSeconds(1)).state)
        assertEquals(TurnDaemonProjection.State.STALLED, observe(lastTick = null, nextTurn = now).state)
    }

    @Test
    fun `expired true cannot mask missing or stale execution`() {
        val old = TurnDaemonObservation("pep", 1, now.minusSeconds(31), now.minusSeconds(1), true)
        val result = observe(old, lastTick = null)
        assertEquals(TurnDaemonProjection.State.UNKNOWN, result.state)
        assertEquals(TurnDaemonProjection.ObservationState.EXPIRED, result.observationState)
        assertNull(result.paused)
        assertNull(result.pausedReason)
    }

    @Test
    fun `missing observation is unknown even with a recent tick marker`() {
        val result = observe(null)
        assertEquals(TurnDaemonProjection.State.UNKNOWN, result.state)
        assertEquals(TurnDaemonProjection.ObservationState.MISSING, result.observationState)
        assertNull(result.paused)
    }

    @Test
    fun `server mismatch future source and reversed receive time are invalid`() {
        for (bad in listOf(source(true).copy(serverId = "other"), source(true).copy(worldId = 2),
            source(true).copy(sourceObservedAt = now.plusSeconds(1)),
            source(true).copy(receivedAt = now.plusSeconds(1)),
            source(true).copy(receivedAt = now.minusSeconds(2)))) {
            val result = observe(bad)
            assertEquals(TurnDaemonProjection.ObservationState.INVALID, result.observationState)
            assertEquals(TurnDaemonProjection.State.UNKNOWN, result.state)
            assertNull(result.paused)
        }
    }

    @Test
    fun `freshness boundary is current and both timestamps survive unchanged`() {
        val source = TurnDaemonObservation("pep", 1, now.minusSeconds(30), now.minusSeconds(1), false)
        val result = observe(source)
        assertEquals(TurnDaemonProjection.ObservationState.CURRENT, result.observationState)
        assertEquals(source.sourceObservedAt, result.sourceObservedAt)
        assertEquals(source.receivedAt, result.receivedAt)
    }

    @Test
    fun `bad future execution and twenty six hours cannot be healthy`() {
        assertEquals(TurnDaemonProjection.State.STALLED, observe(lastTick = now.plusSeconds(301)).state)
        assertEquals(TurnDaemonProjection.State.STALLED, observe(lastTick = now.minusSeconds(26 * 3600L), tickSeconds = 86400).state)
    }

    @Test
    fun `only running catching-up and waiting expose next turn while all stopped states return null`() {
        val next = now.plusSeconds(60)
        for (result in listOf(observe(nextTurn = next), observe(nextTurn = next, catchUp = true),
            observe(lastTick = null, nextTurn = next))) {
            assertEquals(next, result.nextTurnAt)
            assertTrue(result.healthy)
        }
        for (result in listOf(observe(source(true), nextTurn = next),
            observe(lastTick = now.minusSeconds(181), nextTurn = next), observe(null, nextTurn = next))) {
            assertNull(result.nextTurnAt)
            assertFalse(result.healthy)
            assertEquals("degraded", result.healthStatus)
        }
    }

    @Test
    fun `unknown degrades immediately and becomes alert due strictly after three ticks without a cap`() {
        fun unknown(since: Instant, tick: Int = 60) = TurnDaemonProjection.observe("pep", 1, now, null,
            budget, now.minusSeconds(1), now.plusSeconds(60), tick, false, since)
        val boundary = unknown(now.minusSeconds(180))
        assertEquals("pause_observation_unavailable", boundary.failureReason)
        assertEquals("degraded", boundary.healthStatus)
        assertNull(boundary.paused)
        assertNull(boundary.nextTurnAt)
        assertEquals(180L, boundary.unknownSeconds)
        assertFalse(boundary.unknownAlertDue)
        assertTrue(unknown(now.minusSeconds(181)).unknownAlertDue)
        assertFalse(unknown(now.minusSeconds(26 * 3600L), 86400).unknownAlertDue)
        assertTrue(unknown(now.minusSeconds(3 * 86400L + 1), 86400).unknownAlertDue)
    }

}
