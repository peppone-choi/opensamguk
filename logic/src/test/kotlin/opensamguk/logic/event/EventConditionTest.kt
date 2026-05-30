package opensamguk.logic.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FE1 — sealed [EventCondition] DSL (PHP `sammo/Event/Condition/` leaf classes).
 *
 * Faithful port of the PHP `Condition` leaves: ConstBool / Date / DateRelative / RemainNation /
 * Logic(and/or/xor/not) / Interval(throws) / Engine(stub). `eval(env)` returns the boolean only
 * (PHP returns `['value'=>..., 'chain'=>...]` but the `chain` is observational debug data — the
 * dispatcher discards it; only `value` gates the action run, `EventHandler.php:19`).
 */
class EventConditionTest {

    // ── ConstBool (ConstBool.php:11-16) ──────────────────────────────────────
    @Test
    fun `ConstBool returns its fixed value`() {
        assertTrue(EventCondition.ConstBool(true).eval(emptyMap()))
        assertFalse(EventCondition.ConstBool(false).eval(emptyMap()))
    }

    // ── Date (Date.php:36-77) — PHP 2-tuple [year?,month?] comparison ─────────
    @Test
    fun `Date null env returns false (Date_php 37-42)`() {
        // PHP: env===null short-circuits to false BEFORE the missing-key throws.
        assertFalse(EventCondition.Date("==", null, 1).eval(null))
    }

    @Test
    fun `Date null-year only month participates (both sides null in year slot)`() {
        // ["Date","==",null,1] vs month==1 → true regardless of year; month==2 → false.
        val jan = mapOf("year" to 190, "month" to 1)
        val feb = mapOf("year" to 190, "month" to 2)
        assertTrue(EventCondition.Date("==", null, 1).eval(jan))
        assertFalse(EventCondition.Date("==", null, 1).eval(feb))
    }

    @Test
    fun `Date null-month only year participates`() {
        val y190 = mapOf("year" to 190, "month" to 7)
        assertTrue(EventCondition.Date("==", 190, null).eval(y190))
        assertFalse(EventCondition.Date("==", 191, null).eval(y190))
    }

    @Test
    fun `Date lexicographic ordering over the 2-tuple`() {
        // [year=190,month=4] < [year=190,month=7] → equal year, 4<7 → true.
        val env = mapOf("year" to 190, "month" to 4)
        assertTrue(EventCondition.Date("<", 190, 7).eval(env))
        assertFalse(EventCondition.Date(">", 190, 7).eval(env))
        // year dominates: [189,12] < [190,1].
        val late = mapOf("year" to 189, "month" to 12)
        assertTrue(EventCondition.Date("<", 190, 1).eval(late))
    }

    @Test
    fun `Date neq and gte and lte`() {
        val env = mapOf("year" to 190, "month" to 4)
        assertTrue(EventCondition.Date("!=", 190, 5).eval(env))
        assertFalse(EventCondition.Date("!=", 190, 4).eval(env))
        assertTrue(EventCondition.Date(">=", 190, 4).eval(env))
        assertTrue(EventCondition.Date("<=", 190, 4).eval(env))
    }

    @Test
    fun `Date both null throws at construction (Date_php 27-29)`() {
        assertFailsWith<IllegalArgumentException> { EventCondition.Date("==", null, null) }
    }

    @Test
    fun `Date invalid comparator throws (Date_php 23-25)`() {
        assertFailsWith<IllegalArgumentException> { EventCondition.Date("~=", null, 1) }
    }

    @Test
    fun `Date missing env key throws (Date_php 44-50)`() {
        // year non-null but env lacks 'year' → PHP throws InvalidArgumentException.
        assertFailsWith<IllegalArgumentException> {
            EventCondition.Date("==", 190, null).eval(mapOf("month" to 1))
        }
    }

    // ── DateRelative (DateRelative.php:35-82) — year = env.year - env.startyear ─
    @Test
    fun `DateRelative subtracts startyear from year`() {
        // year=192, startyear=190 → relative year = 2; ["DateRelative","==",2,1] @ month 1.
        val env = mapOf("year" to 192, "startyear" to 190, "month" to 1)
        assertTrue(EventCondition.DateRelative("==", 2, 1).eval(env))
        assertFalse(EventCondition.DateRelative("==", 1, 1).eval(env))
    }

    @Test
    fun `DateRelative null env returns false`() {
        assertFalse(EventCondition.DateRelative("==", 1, 1).eval(null))
    }

    @Test
    fun `DateRelative missing startyear throws when year non-null`() {
        assertFailsWith<IllegalArgumentException> {
            EventCondition.DateRelative("==", 1, 1).eval(mapOf("year" to 192, "month" to 1))
        }
    }

    // ── RemainNation (RemainNation.php:30-42) — reads world count, NOT env ────
    @Test
    fun `RemainNation compares world nation count not env`() {
        // The condition counts the GLOBAL nation static info via the env-provided supplier; env date
        // is irrelevant. We model the count as env['__remainNationCount'] (the dispatcher injects the
        // live world.nations count — confirmed against golden; faithful to getAllNationStaticInfo()).
        val env3 = mapOf("year" to 190, "month" to 1, EventCondition.REMAIN_NATION_COUNT_KEY to 3)
        assertTrue(EventCondition.RemainNation(">", 1).eval(env3))
        assertTrue(EventCondition.RemainNation("==", 3).eval(env3))
        assertFalse(EventCondition.RemainNation("<", 3).eval(env3))
        assertTrue(EventCondition.RemainNation("<=", 3).eval(env3))
    }

    // ── Logic (Logic.php:49-108) ─────────────────────────────────────────────
    @Test
    fun `Logic and short-circuits on first false`() {
        val t = EventCondition.ConstBool(true)
        val f = EventCondition.ConstBool(false)
        assertTrue(EventCondition.Logic("and", listOf(t, t)).eval(emptyMap()))
        assertFalse(EventCondition.Logic("and", listOf(t, f, t)).eval(emptyMap()))
        // empty and → true (PHP loop never flips $value).
        assertTrue(EventCondition.Logic("and", emptyList()).eval(emptyMap()))
    }

    @Test
    fun `Logic or short-circuits on first true`() {
        val t = EventCondition.ConstBool(true)
        val f = EventCondition.ConstBool(false)
        assertTrue(EventCondition.Logic("or", listOf(f, t)).eval(emptyMap()))
        assertFalse(EventCondition.Logic("or", listOf(f, f)).eval(emptyMap()))
        // empty or → false.
        assertFalse(EventCondition.Logic("or", emptyList()).eval(emptyMap()))
    }

    @Test
    fun `Logic xor N-ary left-to-right fold`() {
        val t = EventCondition.ConstBool(true)
        val f = EventCondition.ConstBool(false)
        // false xor true = true; true xor true = false; etc.
        assertTrue(EventCondition.Logic("xor", listOf(f, t)).eval(emptyMap()))
        assertFalse(EventCondition.Logic("xor", listOf(t, t)).eval(emptyMap()))
        assertTrue(EventCondition.Logic("xor", listOf(t, t, t)).eval(emptyMap()))
        assertFalse(EventCondition.Logic("xor", listOf(t, t, f)).eval(emptyMap()))
    }

    @Test
    fun `Logic not negates single child`() {
        assertFalse(EventCondition.Logic("not", listOf(EventCondition.ConstBool(true))).eval(emptyMap()))
        assertTrue(EventCondition.Logic("not", listOf(EventCondition.ConstBool(false))).eval(emptyMap()))
    }

    @Test
    fun `Logic not with more than one child throws (Logic_php 24-26)`() {
        val t = EventCondition.ConstBool(true)
        assertFailsWith<IllegalArgumentException> { EventCondition.Logic("not", listOf(t, t)) }
    }

    @Test
    fun `Logic invalid mode throws (Logic_php 20-22)`() {
        assertFailsWith<IllegalArgumentException> {
            EventCondition.Logic("nand", listOf(EventCondition.ConstBool(true)))
        }
    }

    @Test
    fun `Logic mode is case-insensitive (PHP strtolower)`() {
        val t = EventCondition.ConstBool(true)
        assertTrue(EventCondition.Logic("AND", listOf(t)).eval(emptyMap()))
    }

    // ── Interval (Interval.php:6,14) — port as a loud error node ──────────────
    @Test
    fun `Interval eval throws NotImplemented`() {
        // PHP throws in BOTH __construct and eval; the Kotlin leaf's eval throws so any scenario
        // wiring it fails loudly (the codec also refuses to build it — covered in FE2).
        assertFailsWith<NotImplementedError> { EventCondition.Interval.eval(emptyMap()) }
    }

    // ── Engine (Event/Engine.php:9-11) — empty stub ──────────────────────────
    @Test
    fun `Engine is an empty stub evaluating false`() {
        assertFalse(EventCondition.Engine.eval(emptyMap()))
    }
}
