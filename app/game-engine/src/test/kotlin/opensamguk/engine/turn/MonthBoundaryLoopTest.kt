package opensamguk.engine.turn

import opensamguk.logic.tick.ServerClock
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FT3 — wrap the P2 per-general drain in the `executeAllCommand` two-level month-boundary loop.
 *
 * Port target: PHP `TurnExecutionHelper.php:393-517`. The loop keeps the P2 single-pass drain
 * (`compareBy(turnTime,id)` == `ORDER BY turntime ASC, no ASC`) and interleaves ONE
 * `MonthlyPipeline.runMonth` per month boundary. The clean-boundary / processed-count model is kept
 * (we drain ALL due, then flush ONCE per boundary — the divergence from PHP's wall-clock partial
 * checkpoint is documented in [TurnDaemonLifecycle.runMonthBoundaryLoop]).
 */
class MonthBoundaryLoopTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC).toInstant()

    private val turnTerm = 120 // minutes

    @Test
    fun `one month boundary runs one drain pass then one month`() {
        val log = mutableListOf<String>()
        val lc = TurnDaemonLifecycle.MonthBoundaryDriver(
            drain = { upto -> log.add("drain<$upto") },
            runMonth = { nextTurn -> log.add("month@$nextTurn") },
        )
        val prevTurn = ServerClock.cutTurn(at(180, 1, 1, 12, 0), turnTerm)
        val now = ServerClock.addTurn(prevTurn, turnTerm, 1) // exactly one boundary ahead
        val months = lc.run(turntime = prevTurn, now = now, turnTerm = turnTerm, isUnitedState = 0)
        assertEquals(1, months)
        // one in-month drain, one month-pipeline run, one final sub-month drain at `now`.
        assertEquals(3, log.size)
        assertTrue(log[0].startsWith("drain<"))
        assertTrue(log[1].startsWith("month@"))
        assertTrue(log[2].startsWith("drain<")) // final sub-month drain at now
    }

    @Test
    fun `two month catch-up runs drain month drain month`() {
        val log = mutableListOf<String>()
        val lc = TurnDaemonLifecycle.MonthBoundaryDriver(
            drain = { _ -> log.add("drain") },
            runMonth = { _ -> log.add("month") },
        )
        val prevTurn = ServerClock.cutTurn(at(180, 1, 1, 12, 0), turnTerm)
        val now = ServerClock.addTurn(prevTurn, turnTerm, 2) // two boundaries ahead
        val months = lc.run(turntime = prevTurn, now = now, turnTerm = turnTerm, isUnitedState = 0)
        assertEquals(2, months)
        // [drain, month, drain, month] + final sub-month drain.
        assertEquals(listOf("drain", "month", "drain", "month", "drain"), log)
    }

    @Test
    fun `monthly pipeline can be gated while every phase still drains`() {
        val log = mutableListOf<String>()
        var phase = 0
        val lc = TurnDaemonLifecycle.MonthBoundaryDriver(
            drain = { _ -> log.add("drain") },
            runMonth = { _ -> log.add("month") },
            runMonthWhen = {
                phase += 1
                phase == 3
            },
        )
        val prevTurn = ServerClock.cutTurn(at(180, 1, 1, 12, 0), turnTerm)
        val now = ServerClock.addTurn(prevTurn, turnTerm, 3)
        val months = lc.run(turntime = prevTurn, now = now, turnTerm = turnTerm, isUnitedState = 0)
        assertEquals(1, months)
        assertEquals(listOf("drain", "drain", "drain", "month", "drain"), log)
    }

    @Test
    fun `isunited 2 freezes the whole tick - no drain no pipeline`() {
        val log = mutableListOf<String>()
        val lc = TurnDaemonLifecycle.MonthBoundaryDriver(
            drain = { _ -> log.add("drain") },
            runMonth = { _ -> log.add("month") },
        )
        val prevTurn = ServerClock.cutTurn(at(180, 1, 1, 12, 0), turnTerm)
        val now = ServerClock.addTurn(prevTurn, turnTerm, 3)
        val months = lc.run(turntime = prevTurn, now = now, turnTerm = turnTerm, isUnitedState = 2)
        assertEquals(0, months)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `now before turntime is a no-op`() {
        val log = mutableListOf<String>()
        val lc = TurnDaemonLifecycle.MonthBoundaryDriver(
            drain = { _ -> log.add("drain") },
            runMonth = { _ -> log.add("month") },
        )
        val turntime = ServerClock.cutTurn(at(180, 6, 1, 12, 0), turnTerm)
        val now = ServerClock.subTurn(turntime, turnTerm, 1) // earlier than turntime
        val months = lc.run(turntime = turntime, now = now, turnTerm = turnTerm, isUnitedState = 0)
        assertEquals(0, months)
        assertTrue(log.isEmpty())
    }
}
