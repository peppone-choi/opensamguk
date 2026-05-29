package opensamguk.logic.actions

import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
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
 * Port-faithful structural test for the 농지 개간 (che_농지개간) factory over the shared
 * CommerceInvestment algorithm. che_농지개간 differs from che_상업투자 ONLY in
 * cityKey="agri"/actionKey="농업"/name="농지 개간" — so this pins:
 *   - key == "che_농지개간", name == "농지 개간" (WITH space)
 *   - the mutation lands on city.agriculture (NOT commerce)
 *   - meta["intel_exp"] is incremented
 *   - the log uses "농지 개간" WITH space + the correct josa (JosaUtil.pick("농지 개간","을") → "을",
 *     since "간" ends in ㄴ jongsung), i.e. byte-exact log prefix "농지 개간을 ".
 *
 * The exact float golden is pinned separately by the AREA G PHP oracle (G2); here we assert
 * structure + determinism.
 */
class CheNongjigaeganTest {

    private val pipeline = GeneralActionPipeline()

    // FIXTURE INPUT — replaced by the G2-captured golden hiddenSeed before lock.
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"

    private val MONTH = 3   // RNG seed component 4 + the ActionLogger MONTH-format prefix month

    // serializeSeed(hiddenSeed, "generalCommand", year, month, generalId, shortClassName = registry key)
    private fun freshRng() = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 190, MONTH, 42, "che_농지개간")))

    private fun general() = General(
        id = 42,
        nationId = 1,
        cityId = 7,
        leadership = 70,
        strength = 70,
        intel = 80,
        injury = 0,
        experience = 0.0,
        dedication = 0.0,
        officerLevel = 0,
        gold = 1000,
        rice = 1000,
        meta = linkedMapOf("explevel" to 10, "intel_exp" to 3, "max_domestic_critical" to 0.0),
    )

    private fun city() = City(
        id = 7,
        nationId = 1,
        level = 5,
        commerce = 1000, commerceMax = 20000,
        agriculture = 1000, agricultureMax = 20000,
        supplyState = 1,
        frontState = 0,            // not a front city — no debuff
        trust = 50.0,              // INTEGER-valued (G1 invariant) but typed Double (PHP FLOAT)
        meta = linkedMapOf(),
    )

    private val nation = Nation(id = 1, level = 2, capitalCityId = 99)
    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)
    private val date = "12:34"

    private fun action() = cheNongjigaegan(pipeline)

    @Test
    fun `key is che_농지개간 and name is 농지 개간 with the space`() {
        assertEquals("che_농지개간", action().key)
        assertEquals("농지 개간", action().name)
    }

    @Test
    fun `josa for 농지 개간 plus 을 is 을 (간 ends in ㄴ jongsung)`() {
        assertEquals("을", JosaUtil.pick("농지 개간", "을"))
    }

    @Test
    fun `resolve mutates city agriculture (not commerce) and increments intel_exp`() {
        val draft = GeneralActionDraft(general(), city(), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        action().resolve(ctx)

        // mutates agriculture, NOT commerce
        assertTrue(draft.city.agriculture > 1000, "agriculture increased: ${draft.city.agriculture}")
        assertEquals(1000, draft.city.commerce, "commerce untouched by an agri action")
        // meta["intel_exp"]++
        assertEquals(metaInt(general().meta, "intel_exp") + 1, metaInt(draft.general.meta, "intel_exp"), "intel_exp")
    }

    @Test
    fun `log uses 농지 개간 with space and josa 을 — byte-exact prefix`() {
        val draft = GeneralActionDraft(general(), city(), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        action().resolve(ctx)

        assertEquals(1, ctx.logs().size, "exactly one log")
        val log = ctx.logs()[0]
        // byte-exact prefix: ActionLogger MONTH format + name WITH space + josa "을" + " "
        assertTrue(log.startsWith("<C>●</>${MONTH}월:농지 개간을 "), "log prefix; got: $log")
        // carries the <C> scoreText + <1>date</> suffix (shared CommerceInvestment log shape)
        assertTrue(log.contains("<C>"), "scoreText <C> marker")
        assertTrue(log.endsWith("<1>$date</>"), "date suffix")
    }

    @Test
    fun `two runs with the same seed are byte-identical (determinism)`() {
        val a = GeneralActionDraft(general(), city(), nation)
        val ctxA = GeneralActionResolveContext(a, freshRng(), env, MONTH, date)
        action().resolve(ctxA)

        val b = GeneralActionDraft(general(), city(), nation)
        val ctxB = GeneralActionResolveContext(b, freshRng(), env, MONTH, date)
        action().resolve(ctxB)

        assertEquals(a.city.agriculture, b.city.agriculture)
        assertEquals(a.general.gold, b.general.gold)
        assertEquals(a.general.experience, b.general.experience)
        assertEquals(a.general.dedication, b.general.dedication)
        assertEquals(metaInt(a.general.meta, "intel_exp"), metaInt(b.general.meta, "intel_exp"))
        assertEquals(ctxA.logs(), ctxB.logs())
    }
}
