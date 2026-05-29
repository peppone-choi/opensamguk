package opensamguk.logic.actions.develop

import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.CommerceInvestment
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.cheSeongbyeokBosu
import opensamguk.logic.actions.cheSubiGanghwa
import opensamguk.logic.actions.cheChianGanghwa
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DV1 — generalize the shared CommerceInvestment algorithm to drive the strength-stat / city-column
 * develop family (성벽보수→wall, 수비강화→def, 치안강화→secu) on top of the existing intel/comm/agri
 * parameterization. These three new commands differ from che_상업투자 ONLY in cityKey/statKey/
 * actionKey/name (PHP che_성벽보수/수비강화/치안강화 all `extends che_상업투자`, statKey='strength').
 *
 * Pins:
 *  - key/name (WITH space) per command,
 *  - the mutation lands on the RIGHT city column (wall/def/secu),
 *  - the score is STRENGTH-driven (getStrength) not intelligence,
 *  - meta["strength_exp"] (NOT intel_exp) is incremented (PHP increaseVar(statKey.'_exp', 1)),
 *  - the log uses the spaced action name + josa 을/를,
 *  - RNG draw order identical to che_상업투자 (DRAW1 nextRange → DRAW2 choiceUsingWeight → DRAW3 critEx).
 *
 * The exact float golden is pinned separately by the AREA G PHP oracle; here we assert structure +
 * determinism + the parameterization deltas.
 */
class CommerceInvestmentGeneralizeTest {

    private val pipeline = GeneralActionPipeline()
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val MONTH = 3
    private val date = "12:34"
    private val nation = Nation(id = 1, level = 2, capitalCityId = 99)
    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)

    private fun freshRng(rawClass: String) = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 190, MONTH, 42, rawClass)))

    private fun general() = General(
        id = 42, nationId = 1, cityId = 7,
        leadership = 70, strength = 85, intel = 60,
        injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 100000, rice = 100000,
        meta = linkedMapOf(
            "explevel" to 10, "intel_exp" to 3, "strength_exp" to 4, "max_domestic_critical" to 0.0),
    )

    private fun city() = City(
        id = 7, nationId = 1, level = 5,
        commerce = 1000, commerceMax = 20000,
        agriculture = 1000, agricultureMax = 20000,
        security = 1000, securityMax = 20000,
        defense = 1000, defenseMax = 20000,
        wall = 1000, wallMax = 20000,
        supplyState = 1, frontState = 0,
        trust = 50.0,
        meta = linkedMapOf(),
    )

    @Test
    fun `registry resolves the three new commands to CommerceInvestment with correct key and name`() {
        val registry = CommandRegistry(pipeline)
        val wall = registry.resolve("che_성벽보수")
        val def = registry.resolve("che_수비강화")
        val secu = registry.resolve("che_치안강화")
        assertTrue(wall is CommerceInvestment); assertEquals("성벽 보수", wall.name); assertEquals("che_성벽보수", wall.key)
        assertTrue(def is CommerceInvestment); assertEquals("수비 강화", def.name); assertEquals("che_수비강화", def.key)
        assertTrue(secu is CommerceInvestment); assertEquals("치안 강화", secu.name); assertEquals("che_치안강화", secu.key)
    }

    @Test
    fun `성벽보수 mutates city wall (not comm or agri) and increments strength_exp`() {
        val draft = GeneralActionDraft(general(), city(), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng("che_성벽보수"), env, MONTH, date)
        cheSeongbyeokBosu(pipeline).resolve(ctx)

        assertTrue(draft.city.wall > 1000, "wall increased: ${draft.city.wall}")
        assertEquals(1000, draft.city.commerce, "commerce untouched")
        assertEquals(1000, draft.city.agriculture, "agriculture untouched")
        assertEquals(1000, draft.city.defense, "defense untouched")
        assertEquals(1000, draft.city.security, "security untouched")
        // strength_exp++ ; intel_exp untouched
        assertEquals(metaInt(general().meta, "strength_exp") + 1, metaInt(draft.general.meta, "strength_exp"), "strength_exp")
        assertEquals(metaInt(general().meta, "intel_exp"), metaInt(draft.general.meta, "intel_exp"), "intel_exp untouched")
    }

    @Test
    fun `수비강화 mutates city defense`() {
        val draft = GeneralActionDraft(general(), city(), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng("che_수비강화"), env, MONTH, date)
        cheSubiGanghwa(pipeline).resolve(ctx)
        assertTrue(draft.city.defense > 1000, "defense increased: ${draft.city.defense}")
        assertEquals(1000, draft.city.wall, "wall untouched")
    }

    @Test
    fun `치안강화 mutates city security`() {
        val draft = GeneralActionDraft(general(), city(), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng("che_치안강화"), env, MONTH, date)
        cheChianGanghwa(pipeline).resolve(ctx)
        assertTrue(draft.city.security > 1000, "security increased: ${draft.city.security}")
        assertEquals(1000, draft.city.defense, "defense untouched")
    }

    @Test
    fun `log uses the spaced action name and josa — byte-exact prefix`() {
        val draft = GeneralActionDraft(general(), city(), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng("che_성벽보수"), env, MONTH, date)
        cheSeongbyeokBosu(pipeline).resolve(ctx)
        assertEquals(1, ctx.logs().size)
        val log = ctx.logs()[0]
        val josa = JosaUtil.pick("성벽 보수", "을")   // "수" no jongsung -> "를"
        assertTrue(log.startsWith("<C>●</>${MONTH}월:성벽 보수$josa "), "log prefix; got: $log")
        assertTrue(log.endsWith("<1>$date</>"), "date suffix")
    }

    @Test
    fun `score is strength-driven — a high-strength general beats a high-intel general on 성벽보수`() {
        // strength=85 general
        val draftS = GeneralActionDraft(general(), city(), nation)
        cheSeongbyeokBosu(pipeline).resolve(GeneralActionResolveContext(draftS, freshRng("che_성벽보수"), env, MONTH, date))

        // swap: strength low, intel high — strength-driven score must DROP
        val lowStrG = general().copy(strength = 10, intel = 85)
        val draftL = GeneralActionDraft(lowStrG, city(), nation)
        cheSeongbyeokBosu(pipeline).resolve(GeneralActionResolveContext(draftL, freshRng("che_성벽보수"), env, MONTH, date))

        assertTrue(draftS.city.wall > draftL.city.wall,
            "strength-driven: high-str ${draftS.city.wall} > low-str ${draftL.city.wall}")
    }

    @Test
    fun `two runs with the same seed are byte-identical (determinism)`() {
        val a = GeneralActionDraft(general(), city(), nation)
        cheSubiGanghwa(pipeline).resolve(GeneralActionResolveContext(a, freshRng("che_수비강화"), env, MONTH, date))
        val b = GeneralActionDraft(general(), city(), nation)
        cheSubiGanghwa(pipeline).resolve(GeneralActionResolveContext(b, freshRng("che_수비강화"), env, MONTH, date))
        assertEquals(a.city.defense, b.city.defense)
        assertEquals(a.general.gold, b.general.gold)
        assertEquals(a.general.experience, b.general.experience)
        assertEquals(metaInt(a.general.meta, "strength_exp"), metaInt(b.general.meta, "strength_exp"))
    }
}
