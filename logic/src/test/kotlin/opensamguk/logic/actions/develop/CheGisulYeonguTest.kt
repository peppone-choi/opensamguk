package opensamguk.logic.actions.develop

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domestic.techLimit
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DV2 — che_기술연구 (PHP che_기술연구.php:25-143, `extends che_상업투자`).
 *
 * Deltas from che_상업투자:
 *  - NO RemainCityCapacity constraint (init drops it; the other 6 constraints stay).
 *  - NO front-debuff.
 *  - statKey='intel', actionKey='기술', name='기술 연구'.
 *  - exp = round(score)*0.7, ded = round(score)*1.0 (computed BEFORE the TechLimit /4).
 *  - if TechLimit(startYear, year, nation.tech) then score /= 4 (AFTER exp/ded + log).
 *  - genCount = valueFit(nation.gennum, initialNationGenLimit=10).
 *  - nation.tech += score/genCount  (NATION write, float).
 *  - log scoreText = number_format(round(score), 0) — the PRE-/4 rounded score (PHP line 104 < 117).
 */
class CheGisulYeonguTest {

    private val pipeline = GeneralActionPipeline()
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val MONTH = 3
    private val date = "12:34"

    private fun freshRng() = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 190, MONTH, 42, "che_기술연구")))

    private fun general() = General(
        id = 42, nationId = 1, cityId = 7,
        leadership = 70, strength = 70, intel = 80,
        injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 100000, rice = 100000,
        meta = linkedMapOf("explevel" to 10, "intel_exp" to 3, "max_domestic_critical" to 0.0),
    )

    private fun city() = City(
        id = 7, nationId = 1, level = 5,
        commerce = 1000, commerceMax = 20000,
        agriculture = 1000, agricultureMax = 20000,
        supplyState = 1, frontState = 0,
        trust = 50.0, meta = linkedMapOf(),
    )

    // gennum=10 rides meta; tech low enough that TechLimit is FALSE at relYear 6.
    private fun nation(tech: Double = 0.0, gennum: Int = 10) =
        Nation(id = 1, level = 2, capitalCityId = 99, tech = tech, gennum = gennum)

    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)   // relYear=6

    private fun action() = cheGisulYeongu(pipeline)

    // ---- TechLimit helper (func_converter.php:684-697 + getTechLevel:676) ----
    @Test
    fun `techLimit getTechLevel floor div 1000 clamped 0 to 12`() {
        // relYear 6: relMaxTech = valueFit(floor(6/5)+1,1,12) = valueFit(2,1,12)=2 → techLevel>=2 ?
        // tech 0 → techLevel 0 < 2 → false
        assertFalse(techLimit(184, 190, 0.0))
        // tech 2500 → techLevel floor(2.5)=2 >= 2 → true
        assertTrue(techLimit(184, 190, 2500.0))
        // tech 1999 → techLevel 1 < 2 → false
        assertFalse(techLimit(184, 190, 1999.0))
        // clamp: huge tech → techLevel capped 12; relYear 0 → relMaxTech valueFit(0+1,1,12)=1 → 12>=1 true
        assertTrue(techLimit(184, 184, 99000.0))
    }

    @Test
    fun `key and name`() {
        assertEquals("che_기술연구", action().key)
        assertEquals("기술 연구", action().name)
    }

    @Test
    fun `no RemainCityCapacity constraint — only the 6 base constraints`() {
        val ctx = ConstraintContext(actorId = 42, cityId = 7, nationId = 1, mode = ConstraintMode.FULL)
        val names = action().buildConstraints(ctx).map { it.name }
        assertFalse(names.contains("RemainCityCapacity"), "기술연구 has NO RemainCityCapacity; got $names")
        assertTrue(names.contains("ReqGeneralGold"))
        assertEquals(6, names.size, "exactly 6 constraints; got $names")
    }

    @Test
    fun `resolve increments nation tech and intel_exp, decrements gold, leaves city untouched`() {
        val draft = GeneralActionDraft(general(), city(), nation(tech = 0.0, gennum = 10))
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        action().resolve(ctx)

        assertTrue(draft.nation!!.tech > 0.0, "nation.tech increased: ${draft.nation!!.tech}")
        assertEquals(metaInt(general().meta, "intel_exp") + 1, metaInt(draft.general.meta, "intel_exp"), "intel_exp")
        assertTrue(draft.general.gold < 100000, "gold decremented")
        // city columns untouched (기술연구 writes nation.tech, not city)
        assertEquals(1000, draft.city.commerce)
        assertEquals(1000, draft.city.agriculture)
        assertEquals(1, ctx.logs().size, "one log")
        assertTrue(ctx.logs()[0].startsWith("<C>●</>${MONTH}월:기술 연구"), "log prefix: ${ctx.logs()[0]}")
    }

    @Test
    fun `TechLimit branch quarters the nation tech delta but NOT exp or ded`() {
        // no-limit run (tech=0 → techLimit false)
        val noLimit = GeneralActionDraft(general(), city(), nation(tech = 0.0, gennum = 10))
        action().resolve(GeneralActionResolveContext(noLimit, freshRng(), env, MONTH, date))

        // limited run (tech=2500 → techLimit true at relYear 6); identical RNG seed
        val limited = GeneralActionDraft(general(), city(), nation(tech = 2500.0, gennum = 10))
        action().resolve(GeneralActionResolveContext(limited, freshRng(), env, MONTH, date))

        val deltaNoLimit = noLimit.nation!!.tech - 0.0
        val deltaLimited = limited.nation!!.tech - 2500.0
        // tech delta quartered (within float tolerance)
        assertTrue(deltaLimited > 0.0 && deltaNoLimit > 0.0)
        assertEquals(deltaNoLimit / 4.0, deltaLimited, 1e-9, "TechLimit divides the tech delta by 4")
        // exp/ded identical across the two (computed BEFORE the /4)
        assertEquals(noLimit.general.experience, limited.general.experience, 1e-12, "exp unaffected by /4")
        assertEquals(noLimit.general.dedication, limited.general.dedication, 1e-12, "ded unaffected by /4")
    }

    @Test
    fun `genCount divides the tech delta — gennum 20 halves vs gennum 10`() {
        val g10 = GeneralActionDraft(general(), city(), nation(tech = 0.0, gennum = 10))
        action().resolve(GeneralActionResolveContext(g10, freshRng(), env, MONTH, date))
        val g20 = GeneralActionDraft(general(), city(), nation(tech = 0.0, gennum = 20))
        action().resolve(GeneralActionResolveContext(g20, freshRng(), env, MONTH, date))
        assertEquals(g10.nation!!.tech / 2.0, g20.nation!!.tech, 1e-9, "tech delta /= genCount")
    }

    @Test
    fun `determinism — same seed byte-identical`() {
        val a = GeneralActionDraft(general(), city(), nation(tech = 0.0, gennum = 10))
        action().resolve(GeneralActionResolveContext(a, freshRng(), env, MONTH, date))
        val b = GeneralActionDraft(general(), city(), nation(tech = 0.0, gennum = 10))
        action().resolve(GeneralActionResolveContext(b, freshRng(), env, MONTH, date))
        assertEquals(a.nation!!.tech, b.nation!!.tech)
        assertEquals(a.general.gold, b.general.gold)
        assertEquals(a.general.experience, b.general.experience)
    }
}
