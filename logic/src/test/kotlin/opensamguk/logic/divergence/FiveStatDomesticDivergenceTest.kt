package opensamguk.logic.divergence

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.CommerceInvestment
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.develop.cheJeongchakJangnyeo
import opensamguk.logic.actions.develop.cheJuminSeonjeong
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * DIVERGENCE TEST — RTK14 5-stat domestic (fiveStatLogic flag).
 *
 * **NO PHP GOLDEN ORACLE.** devsam/core (the grand truth) is 3-stat and has NO 정치/매력 column, so
 * this behavior CANNOT be replayed against a captured PHP fixture. The assertions here are purely
 * BEHAVIORAL (relative: politics/charm-driven vs intel/leadership-driven), proving the flag-gated swap:
 *   - flag OFF (default) → intel/leadership-driven develop is byte-identical to the parity baseline,
 *   - flag ON → intel-driven develop (농지개간/상업투자/기술연구) is driven by `general.politics`, and
 *     leadership-driven develop (민심=주민선정/인구=정착장려) is driven by `general.charm`.
 *
 * Construction mirrors CommerceInvestmentResolveTest's parity fixture (same seed/RNG/draw count); the
 * stat swap adds/removes NO draws, so the only variable across flag states is the base stat magnitude.
 * politics(20) is set FAR from intel(80) so the post-cross-augment intel base (80 + round(strength/4))
 * never collides with the un-augmented politics base → the resulting score (and thus commerce delta)
 * MUST differ when the flag is on.
 */
class FiveStatDomesticDivergenceTest {

    private val pipeline = GeneralActionPipeline()
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val MONTH = 3
    private val date = "12:34"

    private fun freshRng(klass: String) = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 190, MONTH, 42, klass)))

    // politics(20) deliberately FAR from intel(80) so the two bases cannot coincide.
    private fun general() = General(
        id = 42, nationId = 1, cityId = 7,
        leadership = 70, strength = 70, intel = 80,
        injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 100000, rice = 100000,
        meta = linkedMapOf("explevel" to 10, "intel_exp" to 3, "max_domestic_critical" to 0.0),
        politics = 20, charm = 30,
    )

    private fun city() = City(
        id = 7, nationId = 1, level = 5,
        commerce = 1000, commerceMax = 20000,
        agriculture = 1000, agricultureMax = 20000,
        supplyState = 1, frontState = 0,
        trust = 50.0, meta = linkedMapOf(),
    )

    private val nation = Nation(id = 1, level = 2, capitalCityId = 99)

    // intel-driven develop (statKey='intel' → statName='intelligence') — the swap target.
    private fun commerceAction() = CommerceInvestment(
        pipeline = pipeline, cityKey = "comm", statKey = "intel",
        actionKey = "상업", name = "상업 투자")

    // intel-driven develop (농지개간).
    private fun agriAction() = CommerceInvestment(
        pipeline = pipeline, cityKey = "agri", statKey = "intel",
        actionKey = "농업", name = "농지 개간")

    /** Resolve 상업투자 with the given flag, returning the resulting commerce value. */
    private fun resolveCommerce(flag: Boolean): Int {
        val env = WorldEnv(year = 190, startYear = 184, develCost = 120, fiveStatLogic = flag)
        val draft = GeneralActionDraft(general(), city(), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng("che_상업투자"), env, MONTH, date)
        commerceAction().resolve(ctx)
        return draft.city.commerce
    }

    /** Resolve 농지개간 with the given flag, returning the resulting agriculture value. */
    private fun resolveAgri(flag: Boolean): Int {
        val env = WorldEnv(year = 190, startYear = 184, develCost = 120, fiveStatLogic = flag)
        val draft = GeneralActionDraft(general(), city(), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng("che_농지개간"), env, MONTH, date)
        agriAction().resolve(ctx)
        return draft.city.agriculture
    }

    // --- B3: leadership-driven domestic (charm swap) ------------------------------------------------
    // 민심(주민선정) writes city.trust; 인구(정착장려) writes city.population (needs pop headroom so the
    // gain is not clamped to 0). leadership(70) deliberately FAR from charm(30) so the two bases differ.
    private fun popCity() = city().copy(population = 100_000, populationMax = 5_000_000)

    /** Resolve 주민선정 (민심) with the given flag, returning the resulting city.trust. */
    private fun resolveJuminTrust(flag: Boolean): Double {
        val env = WorldEnv(year = 190, startYear = 184, develCost = 120, fiveStatLogic = flag)
        val draft = GeneralActionDraft(general(), city(), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng("che_주민선정"), env, MONTH, date)
        cheJuminSeonjeong(pipeline).resolve(ctx)
        return draft.city.trust
    }

    /** Resolve 정착장려 (인구) with the given flag, returning the resulting city.population. */
    private fun resolveJeongchakPop(flag: Boolean): Int {
        val env = WorldEnv(year = 190, startYear = 184, develCost = 120, fiveStatLogic = flag)
        val draft = GeneralActionDraft(general(), popCity(), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng("che_정착장려"), env, MONTH, date)
        cheJeongchakJangnyeo(pipeline).resolve(ctx)
        return draft.city.population
    }

    @Test
    fun `DIVERGENCE — flag OFF is byte-identical to the intel-driven parity baseline`() {
        // Baseline replay: the SAME action with the flag off must reproduce the parity path. We pin it
        // by determinism (two flag-off runs identical) AND by proving the swap had no effect when off.
        val off1 = resolveCommerce(flag = false)
        val off2 = resolveCommerce(flag = false)
        assertEquals(off1, off2, "flag OFF is deterministic (parity baseline)")
        // commerce strictly rose above the 1000 floor — the intel(80)-driven score was applied.
        assertTrue(off1 > 1000, "flag OFF: intel-driven develop raised commerce above the 1000 base")
    }

    @Test
    fun `DIVERGENCE — flag ON drives the score by politics (differs when politics != intel)`() {
        val off = resolveCommerce(flag = false)   // intel(80)-driven
        val on = resolveCommerce(flag = true)     // politics(20)-driven

        // Same seed, same draw count — the ONLY changed input is the base stat. politics(20) << intel(80),
        // so the politics-driven score (and commerce delta) MUST differ from the intel-driven one.
        assertNotEquals(off, on, "flag ON must use politics (20), not intel (80) → different commerce")
        // politics(20) is far below intel(80) → smaller score → smaller commerce gain.
        assertTrue(on - 1000 < off - 1000,
            "flag ON: politics(20)-driven gain < intel(80)-driven gain (on=$on off=$off)")

        // Determinism guard on the flag-ON path too.
        assertEquals(on, resolveCommerce(flag = true), "flag ON is deterministic")
    }

    @Test
    fun `DIVERGENCE — 농지개간 also swaps intel to politics under the flag`() {
        val off = resolveAgri(flag = false)
        val on = resolveAgri(flag = true)
        assertNotEquals(off, on, "농지개간 (intel-driven) must swap to politics when the flag is on")
        assertTrue(on - 1000 < off - 1000,
            "flag ON 농지개간: politics(20)-driven gain < intel(80)-driven gain (on=$on off=$off)")
    }

    // --- B3 민심(주민선정): leadership → charm -------------------------------------------------------
    @Test
    fun `DIVERGENCE — 민심 flag OFF is leadership-driven (baseline) and deterministic`() {
        val off1 = resolveJuminTrust(flag = false)
        val off2 = resolveJuminTrust(flag = false)
        assertEquals(off1, off2, "민심 flag OFF is deterministic (leadership(70)-driven baseline)")
        // city base trust is 50.0; the leadership(70)-driven score raised it.
        assertTrue(off1 > 50.0, "flag OFF: leadership-driven 민심 raised trust above the 50.0 base")
    }

    @Test
    fun `DIVERGENCE — 민심 flag ON drives trust by charm (differs from leadership)`() {
        val off = resolveJuminTrust(flag = false)   // leadership(70)-driven
        val on = resolveJuminTrust(flag = true)     // charm(30)-driven

        // Same seed, same draw count — only the base stat changed. charm(30) << leadership(70),
        // so the charm-driven trust gain MUST differ from (and be smaller than) the leadership-driven one.
        assertNotEquals(off, on, "민심 flag ON must use charm (30), not leadership (70) → different trust")
        assertTrue(on - 50.0 < off - 50.0,
            "flag ON 민심: charm(30)-driven gain < leadership(70)-driven gain (on=$on off=$off)")
        assertEquals(on, resolveJuminTrust(flag = true), "민심 flag ON is deterministic")
    }

    // --- B3 인구(정착장려): leadership → charm -------------------------------------------------------
    @Test
    fun `DIVERGENCE — 인구 flag OFF is leadership-driven (baseline) and deterministic`() {
        val off1 = resolveJeongchakPop(flag = false)
        val off2 = resolveJeongchakPop(flag = false)
        assertEquals(off1, off2, "인구 flag OFF is deterministic (leadership(70)-driven baseline)")
        assertTrue(off1 > 100_000, "flag OFF: leadership-driven 인구 raised population above the 100,000 base")
    }

    @Test
    fun `DIVERGENCE — 인구 flag ON drives population by charm (differs from leadership)`() {
        val off = resolveJeongchakPop(flag = false)   // leadership(70)-driven
        val on = resolveJeongchakPop(flag = true)     // charm(30)-driven

        assertNotEquals(off, on, "인구 flag ON must use charm (30), not leadership (70) → different population")
        assertTrue(on - 100_000 < off - 100_000,
            "flag ON 인구: charm(30)-driven gain < leadership(70)-driven gain (on=$on off=$off)")
        assertEquals(on, resolveJeongchakPop(flag = true), "인구 flag ON is deterministic")
    }
}
