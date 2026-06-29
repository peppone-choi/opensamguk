package opensamguk.logic.tick

import opensamguk.common.constants.GameConst
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * FT1 — pure calendar helpers for the monthly tick.
 *
 * Faithful port of PHP `func.php` (grand truth):
 * - `turnDate` (`:1250-1275`) — `num = intdiv(cutTurn(curtime) - startTime, turnTerm*60)` in
 *   SECONDS, then `date = startYear*12 + num`, `year = intdiv(date,12)`, `month = 1 + date%12`
 *   (1-based). The math is INTEGER FLOOR-division (`intdiv`), NEVER `Util::round`/[phpRound] — the
 *   two must never be swapped (plan FT1 pin).
 * - `addTurn`/`subTurn` (`:924-945`) — add/subtract `turnTerm * turn` MINUTES.
 * - `cutTurn` (`:946-967`) — floor an instant to the `turnTerm`-minute grid anchored at the prior
 *   calendar day 01:00 (PHP `$baseDate = midnight(date) - P1D + PT1H`).
 * - `cutDay` (`:969-...`) — the day-sync variant (grid of `12 * turnTerm` minutes).
 *
 * Time is modeled as [Instant] while wall-clock dates are interpreted in the fixed opensamguk server
 * zone, [SERVER_ZONE] (fixed KST, UTC+09:00). `turnTerm` is in MINUTES, so one turn step is
 * `turnTerm * 60` seconds.
 *
 * The helpers are PURE and change-gated at the call site: [turnDate] returns `(year, month, phase)`;
 * the caller writes the world clock only if it changed. Opensamguk intentionally uses the 삼모
 * ten-day calendar: 상순/중순/하순, 36 turns per year.
 */
object ServerClock {
    val SERVER_ZONE: ZoneId = ZoneOffset.ofHours(9)

    /** PHP `addTurn`: `$date + PT{turnTerm*turn}M`. */
    fun addTurn(date: Instant, turnTerm: Int, turn: Int = 1): Instant =
        date.plus(Duration.ofMinutes((turnTerm.toLong()) * turn))

    /** PHP `subTurn`: `$date - PT{turnTerm*turn}M`. */
    fun subTurn(date: Instant, turnTerm: Int, turn: Int = 1): Instant =
        date.minus(Duration.ofMinutes((turnTerm.toLong()) * turn))

    /**
     * PHP `cutTurn`: floor [date] to the `turnTerm`-minute grid measured from the anchor
     * `midnight(date's day) - 1 day + 1 hour` (i.e. the PRIOR Korean calendar day at 01:00).
     *
     * `diffMin = intdiv(date - anchor, 60); diffMin -= diffMin % turnTerm; result = anchor + diffMin`.
     */
    fun cutTurn(date: Instant, turnTerm: Int): Instant {
        val anchor = anchorOf(date)
        val diffMin = floorDiv(date.epochSecond - anchor.epochSecond, 60L)
        val floored = diffMin - Math.floorMod(diffMin, turnTerm.toLong())
        return anchor.plus(Duration.ofMinutes(floored))
    }

    /**
     * PHP `cutDay`: the day-sync variant — the grid step is `12 * turnTerm` minutes (a "game day"),
     * anchored identically to [cutTurn].
     */
    fun cutDay(date: Instant, turnTerm: Int): Instant {
        val anchor = anchorOf(date)
        val baseGap = 12L * turnTerm
        val diffMin = floorDiv(date.epochSecond - anchor.epochSecond, 60L)
        val floored = diffMin - Math.floorMod(diffMin, baseGap)
        return anchor.plus(Duration.ofMinutes(floored))
    }

    /**
     * Resolve the game `(year, month, phase)` of [curtime] from the install epoch.
     *
     * `num = intdiv(cutTurn(curtime) - startTime, turnTerm*60)` (SECONDS, floor); then map the
     * absolute turn onto 12 months × 3 phases. Phase is 1=상순, 2=중순, 3=하순.
     *
     * Change-gated by the CALLER: this returns the pair; the caller writes the world clock only when
     * it differs from the stored `(year, month)`.
     */
    fun turnDate(curtime: Instant, startYear: Int, startTime: Instant, turnTerm: Int): GameDate {
        val curturn = cutTurn(curtime, turnTerm)
        val num = floorDiv(curturn.epochSecond - startTime.epochSecond, turnTerm.toLong() * 60L)
        return dateFromElapsedTurns(startYear, num)
    }

    fun advance(date: GameDate, turns: Int): GameDate {
        val absolute = date.year.toLong() * GameConst.turnsPerYear +
            (date.month - 1L) * GameConst.phasesPerMonth +
            (date.phase - 1L) +
            turns
        return dateFromAbsoluteTurn(absolute)
    }

    fun turnPhaseText(phase: Int): String = when (phase) {
        1 -> "상순"
        2 -> "중순"
        3 -> "하순"
        else -> "상순"
    }

    /**
     * The month boundaries strictly between `prevTurn` and `now` (exclusive-of-prev, inclusive-of-now),
     * mirroring the PHP `executeAllCommand` `while (nextTurn <= now)` catch-up loop. Each element is
     * the `nextTurn` at which a month-pipeline run fires. `prevTurn` is the last processed boundary
     * (already cut to the grid by the caller).
     */
    fun monthBoundaries(prevTurn: Instant, now: Instant, turnTerm: Int): List<Instant> {
        val out = ArrayList<Instant>()
        var nextTurn = addTurn(prevTurn, turnTerm)
        while (!nextTurn.isAfter(now)) {
            out.add(nextTurn)
            nextTurn = addTurn(nextTurn, turnTerm)
        }
        return out
    }

    /** PHP `$baseDate = new DateTime(date->format('Y-m-d')); $baseDate->sub(P1D)->add(PT1H);` */
    private fun anchorOf(date: Instant): Instant {
        val day: LocalDate = date.atZone(SERVER_ZONE).toLocalDate()
        val priorMidnight = day.minusDays(1).atStartOfDay(SERVER_ZONE).toInstant()
        return priorMidnight.plus(Duration.ofHours(1))
    }

    /** Integer floor-division (PHP `intdiv` on a non-negative pair; floor-safe for negatives). */
    private fun floorDiv(a: Long, b: Long): Long = Math.floorDiv(a, b)

    private fun dateFromElapsedTurns(startYear: Int, elapsedTurns: Long): GameDate =
        dateFromAbsoluteTurn(startYear.toLong() * GameConst.turnsPerYear + elapsedTurns)

    private fun dateFromAbsoluteTurn(absolute: Long): GameDate {
        val year = floorDiv(absolute, GameConst.turnsPerYear.toLong()).toInt()
        val withinYear = Math.floorMod(absolute, GameConst.turnsPerYear.toLong())
        val month = (withinYear / GameConst.phasesPerMonth.toLong() + 1L).toInt()
        val phase = (withinYear % GameConst.phasesPerMonth.toLong() + 1L).toInt()
        return GameDate(year, month, phase)
    }
}

data class GameDate(
    val year: Int,
    val month: Int,
    val phase: Int = 1,
) {
    val phaseText: String get() = ServerClock.turnPhaseText(phase)
}
