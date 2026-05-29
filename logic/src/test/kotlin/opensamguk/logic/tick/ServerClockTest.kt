package opensamguk.logic.tick

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals

/**
 * FT1 — `ServerClock` (turnDate / addTurn / cutTurn pure helpers).
 *
 * Port target: PHP `func.php:1250-1275` (turnDate), `:924-945` (addTurn/subTurn), `:946-967`
 * (cutTurn/cutDay). All time math is `intdiv` FLOOR-division (NOT `Util::round`) — the two must
 * never be swapped. Time is modeled as `java.time.Instant`; `turnTerm` is in MINUTES (PHP
 * `PT{turnterm}M`), so a turn step is `turnTerm * 60` seconds.
 */
class ServerClockTest {

    /** UTC instant for a wall-clock date-time (PHP `DateTime` strings are wall-clock). */
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Instant =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, ZoneOffset.UTC).toInstant()

    @Test
    fun `addTurn adds turnTerm minutes times turn`() {
        val base = at(2024, 1, 1, 12, 0)
        // turnTerm=120 minutes, 1 turn => +2h
        assertEquals(at(2024, 1, 1, 14, 0), ServerClock.addTurn(base, turnTerm = 120, turn = 1))
        // 3 turns of 30 minutes => +90 minutes
        assertEquals(at(2024, 1, 1, 13, 30), ServerClock.addTurn(base, turnTerm = 30, turn = 3))
    }

    @Test
    fun `cutTurn floors to the turnTerm grid anchored at yesterday 0100`() {
        // anchor = (date's day) - 1 day + 1h = the prior calendar day at 01:00.
        // 2024-01-02 12:00 with turnTerm=120: anchor=2024-01-01 01:00.
        // diffMin = (12:00 next day) - (01:00 prior day) = 35h*60 = 2100 min; 2100 % 120 = 60;
        // floored diffMin = 2040 -> anchor + 2040m = 2024-01-01 01:00 + 34h = 2024-01-02 11:00.
        assertEquals(at(2024, 1, 2, 11, 0), ServerClock.cutTurn(at(2024, 1, 2, 12, 0), turnTerm = 120))
        // already on grid: 2024-01-02 11:00 stays put.
        assertEquals(at(2024, 1, 2, 11, 0), ServerClock.cutTurn(at(2024, 1, 2, 11, 0), turnTerm = 120))
    }

    @Test
    fun `turnDate computes year and 1-based month via intdiv floor`() {
        // startYear=180, startTime anchor, turnTerm=120.
        val startTime = at(180, 1, 1, 0, 0) // not used directly except as the epoch reference below
        // Build curtime so that num lands on a known value. Pick startTime on the grid.
        // Use a startTime already cut to grid to avoid double-cut surprises.
        val start = ServerClock.cutTurn(startTime, 120)
        // 0 turns elapsed -> date = startYear*12 + 0 = 2160 -> year=180, month=1.
        val (y0, m0) = ServerClock.turnDate(curtime = start, startYear = 180, startTime = start, turnTerm = 120)
        assertEquals(180, y0)
        assertEquals(1, m0)
        // 11 turns elapsed -> date = 2160 + 11 = 2171 -> year=180, month=1+(2171%12)=1+11=12.
        val cur11 = ServerClock.addTurn(start, 120, 11)
        val (y11, m11) = ServerClock.turnDate(cur11, 180, start, 120)
        assertEquals(180, y11)
        assertEquals(12, m11)
        // 12 turns elapsed -> date = 2172 -> year=181, month=1 (year boundary, 1-based month).
        val cur12 = ServerClock.addTurn(start, 120, 12)
        val (y12, m12) = ServerClock.turnDate(cur12, 180, start, 120)
        assertEquals(181, y12)
        assertEquals(1, m12)
    }

    @Test
    fun `turnDate intdiv floors a partial turn down`() {
        val start = ServerClock.cutTurn(at(180, 1, 1, 0, 0), 120)
        // 1.5 turns = +180 minutes; cutTurn floors back to 1 turn => still month 2 (date 2161).
        val cur = ServerClock.addTurn(start, 120, 1).plusSeconds(60 * 60) // +1 turn then +1h (half a 120-min turn)
        val (y, m) = ServerClock.turnDate(cur, 180, start, 120)
        assertEquals(180, y)
        assertEquals(2, m) // date=2161 -> 1+(2161%12)=1+1=2
    }
}
