package opensamguk.engine.turn

import opensamguk.logic.tick.ServerClock
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonthBoundaryLoopTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ServerClock.SERVER_ZONE).toInstant()

    private val turnTerm = 120 // minutes

    @Test
    fun `exact boundary is drained once`() {
        val drains = mutableListOf<Instant>()
        val lc = TurnDaemonLifecycle.MonthBoundaryDriver(
            drain = { upto -> drains += upto },
            runMonth = {},
        )
        val prevTurn = ServerClock.cutTurn(at(180, 1, 1, 12, 0), turnTerm)
        val boundary = ServerClock.addTurn(prevTurn, turnTerm, 1)

        lc.run(turntime = prevTurn, now = boundary, turnTerm = turnTerm, isUnitedState = 0)

        assertEquals(listOf(boundary), drains)
    }

    @Test
    fun `one boundary runs one drain pass then one month run`() {
        val log = mutableListOf<String>()
        val lc = TurnDaemonLifecycle.MonthBoundaryDriver(
            drain = { upto -> log.add("drain<$upto") },
            runMonth = { nextTurn -> log.add("month@$nextTurn") },
        )
        val prevTurn = ServerClock.cutTurn(at(180, 1, 1, 12, 0), turnTerm)
        val now = ServerClock.addTurn(prevTurn, turnTerm, 1) // exactly one boundary ahead
        val months = lc.run(turntime = prevTurn, now = now, turnTerm = turnTerm, isUnitedState = 0)
        assertEquals(1, months)
        assertEquals(2, log.size)
        assertTrue(log[0].startsWith("drain<"))
        assertTrue(log[1].startsWith("month@"))
    }

    @Test
    fun `two boundaries run drain month drain month`() {
        val log = mutableListOf<String>()
        val lc = TurnDaemonLifecycle.MonthBoundaryDriver(
            drain = { _ -> log.add("drain") },
            runMonth = { _ -> log.add("month") },
        )
        val prevTurn = ServerClock.cutTurn(at(180, 1, 1, 12, 0), turnTerm)
        val now = ServerClock.addTurn(prevTurn, turnTerm, 2) // two boundaries ahead
        val months = lc.run(turntime = prevTurn, now = now, turnTerm = turnTerm, isUnitedState = 0)
        assertEquals(2, months)
        assertEquals(listOf("drain", "month", "drain", "month"), log)
    }

    @Test
    fun `three phase catch-up advances non-monthly phases after their drains`() {
        val log = mutableListOf<String>()
        var boundary = 0
        val lc = TurnDaemonLifecycle.MonthBoundaryDriver(
            drain = { log += "drain" },
            runMonth = { log += "month" },
            runMonthWhen = {
                boundary += 1
                boundary == 3
            },
            advanceNonMonthlyBoundary = { log += "advance" },
        )
        val prevTurn = ServerClock.cutTurn(at(180, 1, 1, 12, 0), turnTerm)
        val now = ServerClock.addTurn(prevTurn, turnTerm, 3)
        val months = lc.run(turntime = prevTurn, now = now, turnTerm = turnTerm, isUnitedState = 0)
        assertEquals(1, months)
        assertEquals(listOf("drain", "advance", "drain", "advance", "drain", "month"), log)
    }

    @Test
    fun `partial interval after a boundary runs a final drain`() {
        val drains = mutableListOf<Instant>()
        val lc = TurnDaemonLifecycle.MonthBoundaryDriver(
            drain = { upto -> drains += upto },
            runMonth = {},
        )
        val prevTurn = ServerClock.cutTurn(at(180, 1, 1, 12, 0), turnTerm)
        val boundary = ServerClock.addTurn(prevTurn, turnTerm, 1)
        val now = boundary.plusSeconds(60)

        lc.run(turntime = prevTurn, now = now, turnTerm = turnTerm, isUnitedState = 0)

        assertEquals(listOf(boundary, now), drains)
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
