package opensamguk.logic.world

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port-faithful tests for `postUpdateMonthly` Q11-Q17 tail (P3 / AREA B2 / Task POST3) — research Unit 9.
 *
 * PHP grand truth `func_gamerule.php:423-442` (+ `:445-467` checkWander):
 *   Q11 if (year >= startyear+2) checkWander($rng)    — auto-disband wanderer nations via che_해산        // :423-426
 *   Q12 updateGeneralNumber()                          — no rng                                            // :427
 *   Q13 refreshNationStaticInfo()                      — no rng                                            // :428
 *   Q14 checkEmperior()                                — NO rng (천통 detection; drives the UNITED target) // :429-430
 *   Q15 triggerTournament($rng)                        — at most one nextBool(0.4) + optional shuffle      // :432
 *   Q16 registerAuction($rng)                          — EXACTLY two nextBool gates (+ optional sub-draws) // :434
 *   Q17 SetNationFront(nation) per level>0 nation in static-info order — no rng (B3 owns the body)         // :436-441
 *
 * **The monthlyRng is a SINGLE instance consumed in EXACT order Q4 → Q11 → Q15 → Q16** (`:322,425,432,434`).
 * Q4 already drew in POST1; POST3 continues on the SAME instance. Any reordering desyncs every downstream
 * month's draw stream, so the core records the draw order and we HARD-ASSERT the per-call draw counts.
 *
 * **UNITED target (consolidated OQ #10 / SC-3):** Q14 checkEmperior is the in-scope B2 path that drives the
 * `united/5000` event (MergeInheritPointRank) + the isunited transition; it consumes NO monthlyRng. A
 * non-unifying N-month replay never fires it (need not byte-match); G1a pins the unifying case.
 */
class PostUpdateMonthlyTailTest {

    private fun rng() = RandUtil(LiteHashDrbg("monthly|200|1"))

    // ── Q11: checkWander gated on year >= startYear+2 ──

    @Test
    fun `Q11 checkWander runs only when year is at least startYear plus two`() {
        var wanderRan = false
        val consumeWander: RngConsumer = { it.nextRange(0.0, 1.0); wanderRan = true }

        // year < startYear+2 → checkWander skipped, NO draw consumed.
        val r1 = rng()
        postUpdateMonthlyTail(
            year = 185, startYear = 184, rng = r1,
            checkWander = consumeWander, triggerTournament = {}, registerAuction = {},
            setNationFront = { emptyList() },
        )
        assertFalse(wanderRan)

        // year == startYear+2 → checkWander runs.
        val r2 = rng()
        val out = postUpdateMonthlyTail(
            year = 186, startYear = 184, rng = r2,
            checkWander = consumeWander, triggerTournament = {}, registerAuction = {},
            setNationFront = { emptyList() },
        )
        assertTrue(wanderRan)
        assertTrue(out.checkWanderRan)
    }

    // ── Q4 → Q11 → Q15 → Q16 draw order on a SINGLE instance ──

    @Test
    fun `monthlyRng draws happen in Q11 then Q15 then Q16 order on the single instance`() {
        // Each consumer draws a recognizable count; assert the SAME rng is threaded in order.
        val ref = rng()
        // Reference stream: Q11 (1 draw) → Q15 (1 draw) → Q16 (2 draws).
        val q11Draw = ref.nextRange(0.0, 1.0)
        val q15Draw = ref.nextRange(0.0, 1.0)
        val q16DrawA = ref.nextRange(0.0, 1.0)
        val q16DrawB = ref.nextRange(0.0, 1.0)

        val captured = mutableListOf<Double>()
        val rng = rng()
        val out = postUpdateMonthlyTail(
            year = 200, startYear = 184, rng = rng,
            checkWander = { captured += it.nextRange(0.0, 1.0) },
            triggerTournament = { captured += it.nextRange(0.0, 1.0) },
            registerAuction = { captured += it.nextRange(0.0, 1.0); captured += it.nextRange(0.0, 1.0) },
            setNationFront = { emptyList() },
        )
        assertEquals(listOf(q11Draw, q15Draw, q16DrawA, q16DrawB), captured)
        assertEquals(listOf("Q11", "Q15", "Q16"), out.rngDrawOrder)
    }

    @Test
    fun `when checkWander is skipped the draw order starts at Q15`() {
        val captured = mutableListOf<Double>()
        val out = postUpdateMonthlyTail(
            year = 185, startYear = 184, rng = rng(),
            checkWander = { captured += it.nextRange(0.0, 1.0) },   // skipped (year<startYear+2)
            triggerTournament = { captured += it.nextRange(0.0, 1.0) },
            registerAuction = { captured += it.nextRange(0.0, 1.0) },
            setNationFront = { emptyList() },
        )
        assertEquals(2, captured.size)                 // only Q15 + Q16 drew
        assertEquals(listOf("Q15", "Q16"), out.rngDrawOrder)
        assertFalse(out.checkWanderRan)
    }

    // ── Q14 checkEmperior consumes NO rng (its position is between Q13 and Q15, no draw) ──

    @Test
    fun `checkEmperior consumes no monthlyRng - draw count unaffected by united state`() {
        // Whether or not unification happened, the draw count is identical (Q14 takes no rng).
        val capturedUnified = mutableListOf<Double>()
        postUpdateMonthlyTail(
            year = 200, startYear = 184, rng = rng(), isUnited = true,
            checkWander = { capturedUnified += it.nextRange(0.0, 1.0) },
            triggerTournament = { capturedUnified += it.nextRange(0.0, 1.0) },
            registerAuction = { capturedUnified += it.nextRange(0.0, 1.0) },
            setNationFront = { emptyList() },
        )
        val capturedNot = mutableListOf<Double>()
        postUpdateMonthlyTail(
            year = 200, startYear = 184, rng = rng(), isUnited = false,
            checkWander = { capturedNot += it.nextRange(0.0, 1.0) },
            triggerTournament = { capturedNot += it.nextRange(0.0, 1.0) },
            registerAuction = { capturedNot += it.nextRange(0.0, 1.0) },
            setNationFront = { emptyList() },
        )
        assertEquals(capturedNot.size, capturedUnified.size)
    }

    @Test
    fun `Q13 refreshNationStaticInfo is PHP request-local cache only and has no Kotlin world callback`() {
        val order = mutableListOf<String>()
        postUpdateMonthlyTail(
            year = 185, startYear = 184, rng = rng(),
            checkWander = { order += "Q11" },
            triggerTournament = { order += "Q15" },
            registerAuction = { order += "Q16" },
            checkEmperior = { order += "Q14" },
            setNationFront = { order += "Q17"; emptyList() },
        )

        assertEquals(listOf("Q14", "Q15", "Q16", "Q17"), order)
    }

    // ── Q17 SetNationFront runs LAST (after the rng consumers) ──

    @Test
    fun `SetNationFront runs last after all rng consumers`() {
        val order = mutableListOf<String>()
        val out = postUpdateMonthlyTail(
            year = 200, startYear = 184, rng = rng(),
            checkWander = { it.nextRange(0.0, 1.0); order += "wander" },
            triggerTournament = { it.nextRange(0.0, 1.0); order += "tournament" },
            registerAuction = { it.nextRange(0.0, 1.0); order += "auction" },
            setNationFront = { order += "front"; listOf(PostFrontResult(nationId = 1)) },
        )
        assertEquals(listOf("wander", "tournament", "auction", "front"), order)
        assertEquals(1, out.frontResults.size)
    }

    @Test
    fun `SetNationFront result threads through from the injected callback`() {
        val fronts = listOf(PostFrontResult(nationId = 5), PostFrontResult(nationId = 7))
        val out = postUpdateMonthlyTail(
            year = 200, startYear = 184, rng = rng(),
            checkWander = {}, triggerTournament = {}, registerAuction = {},
            setNationFront = { fronts },
        )
        assertEquals(fronts, out.frontResults)
    }
}
