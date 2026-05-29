package opensamguk.logic.constraints

import opensamguk.logic.actions.cheNongjigaegan
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.statview.MemoryStateView
import opensamguk.logic.statview.WorldEnvBuilder
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * P1 Task G3 — the `precheck == full` INVARIANT (single shared constraint library).
 *
 * This is the structural proof that there is exactly ONE constraint library and that any
 * precheck-vs-full divergence is ONLY ever data-freshness, never logic drift: the SAME
 * `che_농지개간.buildConstraints` is evaluated through the SAME [evaluateConstraints] twice over the
 * SAME [MemoryStateView] — once in `mode=PRECHECK`, once in `mode=FULL` — and the results are
 * asserted IDENTICAL. (The complementary cross-call-site proof that the two REAL apps agree lives
 * in `:app:game-engine`'s `PrecheckFullCrossCallSiteTest`.)
 *
 * Fixture (the E2 fixture, transcribed): nation 1 (level 7, capital 5) / city 5 (owned, supplied,
 * agri 4000 < 8000) / general 10 in city 5. world env year 200 / startYear 190 -> develCost 40,
 * so the 농지개간 cost (= develCost on the empty P1 pipeline) is 40.
 */
class PrecheckEqualsFullTest {

    private val pipeline = GeneralActionPipeline()
    private val action = cheNongjigaegan(pipeline)

    private val YEAR = 200
    private val START_YEAR = 190
    private val env = WorldEnvBuilder.envMap(YEAR, START_YEAR)   // {year:200, startYear:190, develCost:40}

    private fun general(gold: Int = 4000) = General(
        id = 10, nationId = 1, cityId = 5,
        leadership = 70, strength = 30, intel = 95, injury = 0,
        experience = 1200.0, dedication = 900.0, officerLevel = 5,
        gold = gold, rice = 3000,
        meta = linkedMapOf("explevel" to 4, "intel_exp" to 12, "max_domestic_critical" to 3.5),
    )

    private fun city(agri: Int = 4000) = City(
        id = 5, nationId = 1, level = 5,
        commerce = 3000, commerceMax = 8000, agriculture = agri, agricultureMax = 8000,
        supplyState = 1, frontState = 0, trust = 82.0,
    )

    private fun nation() = Nation(id = 1, level = 7, capitalCityId = 5)

    private fun view(general: General = general(), city: City = city(), nation: Nation = nation()) =
        MemoryStateView(
            generals = linkedMapOf(general.id to general),
            cities = linkedMapOf(city.id to city),
            nations = linkedMapOf(nation.id to nation),
            env = env,
        )

    private fun ctx(mode: ConstraintMode) = ConstraintContext(
        actorId = 10, cityId = 5, nationId = 1, env = env, mode = mode,
    )

    /** Evaluate the SAME action's constraints over the SAME [view] in the given [mode]. */
    private fun evaluate(view: MemoryStateView, mode: ConstraintMode): ConstraintResult {
        val c = ctx(mode)
        return evaluateConstraints(action.buildConstraints(c), c, view)
    }

    @Test
    fun `same view yields identical Allow in PRECHECK and FULL`() {
        val view = view()
        val precheck = evaluate(view, ConstraintMode.PRECHECK)
        val full = evaluate(view, ConstraintMode.FULL)

        // Allow == Allow — the same shared library, the same answer, regardless of mode.
        assertEquals(ConstraintResult.Allow, precheck, "precheck over the fresh view is Allow")
        assertEquals(ConstraintResult.Allow, full, "full over the SAME view is Allow")
        assertEquals(precheck, full, "PRECHECK == FULL over an identical view (no logic drift)")
    }

    @Test
    fun `same view yields identical Deny reason and constraintName in PRECHECK and FULL`() {
        // A denying fixture: agriculture == agricultureMax -> RemainCityCapacity denies in BOTH modes.
        val denyView = view(city = city(agri = 8000))
        val precheck = evaluate(denyView, ConstraintMode.PRECHECK)
        val full = evaluate(denyView, ConstraintMode.FULL)

        val pDeny = assertIs<ConstraintResult.Deny>(precheck, "precheck denies a full city")
        val fDeny = assertIs<ConstraintResult.Deny>(full, "full denies the SAME full city")

        // Deny reason + constraintName IDENTICAL — proves the deny path is the one shared library too.
        assertEquals("농지 개간은 충분합니다.", pDeny.reason)
        assertEquals("RemainCityCapacity", pDeny.constraintName)
        assertEquals(pDeny.reason, fDeny.reason, "deny reason identical across modes")
        assertEquals(pDeny.constraintName, fDeny.constraintName, "constraintName identical across modes")
        assertEquals(precheck, full, "PRECHECK == FULL Deny (reason + name) over an identical view")
    }

    @Test
    fun `designed divergence world mutates between precheck and full is a clean deny-to-rest not a parity failure`() {
        // §1a designed-divergence: the precheck reads a FRESH world (gold 4000 >= cost 40) -> AVAILABLE.
        val freshView = view(general = general(gold = 4000))
        val precheck = evaluate(freshView, ConstraintMode.PRECHECK)
        assertEquals(ConstraintResult.Allow, precheck, "precheck on the fresh world is AVAILABLE")

        // Between the precheck and the daemon's full pass the world MUTATES — the general's gold
        // drops below the cost (e.g. spent on another command first). The daemon overlays the STALER
        // world (gold 10 < cost 40) and the SAME constraint library now DENIES.
        val staleView = view(general = general(gold = 10))
        val full = evaluate(staleView, ConstraintMode.FULL)

        val fDeny = assertIs<ConstraintResult.Deny>(full, "full on the mutated world denies on funds")
        assertEquals("자금이 모자랍니다.", fDeny.reason, "the funds deny string (PHP getFailString)")
        assertEquals("ReqGeneralGold", fDeny.constraintName)

        // This divergence is DATA-FRESHNESS, not logic drift: the EXACT SAME stale view in PRECHECK
        // mode produces the EXACT SAME Deny. A precheck over the stale world would have denied too —
        // the only difference is WHICH snapshot each mode saw, never WHICH logic ran.
        val staleInPrecheck = evaluate(staleView, ConstraintMode.PRECHECK)
        assertEquals(full, staleInPrecheck, "same (stale) view -> same Deny in both modes (clean deny->rest)")
    }
}
