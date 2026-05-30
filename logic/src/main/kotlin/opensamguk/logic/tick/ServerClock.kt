package opensamguk.logic.tick

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
 * Time is modeled as [Instant] (UTC), matching the engine world model. `turnTerm` is in MINUTES, so
 * one turn step is `turnTerm * 60` seconds. Wall-clock dates are interpreted in UTC (the daemon runs
 * on a single fixed zone; the parity capture pins the same).
 *
 * The helpers are PURE and change-gated at the call site: [turnDate] returns `(year, month)`; the
 * caller writes the world clock only if it changed (PHP `if ($admin['month'] != $month || ...)`).
 */
object ServerClock {

    /** PHP `addTurn`: `$date + PT{turnTerm*turn}M`. */
    fun addTurn(date: Instant, turnTerm: Int, turn: Int = 1): Instant =
        date.plus(Duration.ofMinutes((turnTerm.toLong()) * turn))

    /** PHP `subTurn`: `$date - PT{turnTerm*turn}M`. */
    fun subTurn(date: Instant, turnTerm: Int, turn: Int = 1): Instant =
        date.minus(Duration.ofMinutes((turnTerm.toLong()) * turn))

    /**
     * PHP `cutTurn`: floor [date] to the `turnTerm`-minute grid measured from the anchor
     * `midnight(date's day) - 1 day + 1 hour` (i.e. the PRIOR calendar day at 01:00 UTC).
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
     * PHP `turnDate`: resolve the game `(year, month)` of [curtime] from the install epoch.
     *
     * `num = intdiv(cutTurn(curtime) - startTime, turnTerm*60)` (SECONDS, floor); then
     * `date = startYear*12 + num`, `year = intdiv(date,12)`, `month = 1 + date%12` (1-based).
     *
     * Change-gated by the CALLER: this returns the pair; the caller writes the world clock only when
     * it differs from the stored `(year, month)`.
     */
    fun turnDate(curtime: Instant, startYear: Int, startTime: Instant, turnTerm: Int): Pair<Int, Int> {
        val curturn = cutTurn(curtime, turnTerm)
        val num = floorDiv(curturn.epochSecond - startTime.epochSecond, turnTerm.toLong() * 60L)
        val date = startYear.toLong() * 12L + num
        val year = floorDiv(date, 12L).toInt()
        val month = (1L + Math.floorMod(date, 12L)).toInt()
        return year to month
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
        val day: LocalDate = date.atZone(ZoneOffset.UTC).toLocalDate()
        val priorMidnight = day.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        return priorMidnight.plus(Duration.ofHours(1))
    }

    /** Integer floor-division (PHP `intdiv` on a non-negative pair; floor-safe for negatives). */
    private fun floorDiv(a: Long, b: Long): Long = Math.floorDiv(a, b)
}
