package opensamguk.logic.actions.trade

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port-faithful resolve + argTest test for the two 1:1 resource-move trade commands
 * (PHP che_증여.php / che_헌납.php — Research Unit 6).
 *
 * 증여 (CheJeungyeo): a DUAL-entity move — adds `amount` of the resource to the DEST general
 *   (`increaseVarWithLimit(resKey, amount)`), subtracts it from the actor floored at 0
 *   (`increaseVarWithLimit(resKey, -amount, 0)`), pushes a PLAIN log on the dest general's own
 *   logger (`...증여 받았습니다.`, ActionLogger::PLAIN → `<C>●</>{body}`) AND a MONTH log on the
 *   actor (`...증여했습니다. <1>date</>`). exp70/ded100/leadership_exp+1. argTest rounds the amount to
 *   the nearest 100 (`Util::round(amount,-2)`), clamps it to [100, maxResourceActionAmount] and rejects
 *   self-target (`destGeneralID == self`).
 *
 * 헌납 (CheHeonnap): a single-entity move — adds `amount` to the NATION treasury (`nation.{gold|rice}`),
 *   subtracts it from the actor floored at 0, pushes only the actor MONTH log. exp70/ded100/
 *   leadership_exp+1.
 *
 * Both: getCommandDetailTitle '(통솔경험)'; 1:1 (no trade rate); `increaseVar(key,0)` is a NO-OP so an
 * amount clamped to 0 (actor below the resource minimum) moves nothing and writes no resource log.
 */
class GiftTributeTest {

    private val pipeline = GeneralActionPipeline()
    private val HIDDEN_SEED = "00000000000000000000000000000000"
    private val MONTH = 4
    private val YEAR = 200
    private val date = "09:30"
    private val env = WorldEnv(year = YEAR, startYear = 184, develCost = 152)

    // --- fixtures -----------------------------------------------------------

    private fun actor(gold: Int = 5000, rice: Int = 5000, arg: Map<String, Any?>? = null) = General(
        id = 10, nationId = 3, cityId = 7,
        leadership = 70, strength = 60, intel = 50, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 5,
        gold = gold, rice = rice,
        lastTurn = LastTurn(command = "증여", arg = arg),
        meta = linkedMapOf("name" to "최강자", "explevel" to 0, "dedlevel" to 0, "leadership_exp" to 2),
    )

    private fun destGeneral(gold: Int = 100, rice: Int = 100) = General(
        id = 22, nationId = 3, cityId = 7,
        leadership = 40, strength = 40, intel = 40, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1,
        gold = gold, rice = rice,
        meta = linkedMapOf("name" to "최약자", "explevel" to 0, "dedlevel" to 0),
    )

    private fun city() = City(
        id = 7, nationId = 3, level = 5,
        commerce = 1000, commerceMax = 20000, agriculture = 1000, agricultureMax = 20000,
        supplyState = 1, frontState = 0, trust = 80.0, meta = linkedMapOf(),
    )

    private fun nation(gold: Int = 1000, rice: Int = 1000) =
        Nation(id = 3, level = 2, capitalCityId = 7, gold = gold, rice = rice)

    private fun rng() = RandUtil(LiteHashDrbg(serializeSeed(HIDDEN_SEED, "generalCommand", YEAR, MONTH, 10, "che_증여")))
    private fun rngTribute() = RandUtil(LiteHashDrbg(serializeSeed(HIDDEN_SEED, "generalCommand", YEAR, MONTH, 10, "che_헌납")))

    // --- registry metadata --------------------------------------------------

    @Test
    fun `keys names categories detail-title and tokens`() {
        val gift = CheJeungyeo(pipeline)
        val tribute = CheHeonnap(pipeline)
        assertEquals("che_증여", gift.key)
        assertEquals("증여", gift.name)
        assertEquals("자원교역", gift.category)
        assertEquals("che_증여", gift.rawClassName)
        assertEquals("증여", gift.lotteryActionName)
        assertEquals("증여(통솔경험)", gift.getCommandDetailTitle())
        assertEquals("che_헌납", tribute.key)
        assertEquals("헌납", tribute.name)
        assertEquals("자원교역", tribute.category)
        assertEquals("헌납(통솔경험)", tribute.getCommandDetailTitle())
    }

    // --- 증여 argTest (Util::round(-2) + valueFit(100,10000) + self-reject) -

    @Test
    fun `gift argTest rounds to hundred clamps to 100-10000 and rejects self`() {
        val gift = CheJeungyeo(pipeline)
        // 1249 → round(-2) → 1200; within [100,10000]
        assertEquals(
            mapOf("isGold" to true, "amount" to 1200, "destGeneralID" to 22),
            gift.argTest(mapOf("isGold" to true, "amount" to 1249, "destGeneralID" to 22), selfId = 10),
        )
        // 49 → round(-2) → 0 → valueFit lower-clamps to 100 (PHP), but then amount>0 holds (=100)
        assertEquals(
            mapOf("isGold" to false, "amount" to 100, "destGeneralID" to 22),
            gift.argTest(mapOf("isGold" to false, "amount" to 49, "destGeneralID" to 22), selfId = 10),
        )
        // 999999 → round(-2) → 1000000 → upper-clamp to 10000
        assertEquals(10000, gift.argTest(mapOf("isGold" to true, "amount" to 999999, "destGeneralID" to 22), selfId = 10)!!["amount"])
        // self-target rejected (destGeneralID == actor self) — PHP argTest returns false → null
        assertNull(gift.argTest(mapOf("isGold" to true, "amount" to 1000, "destGeneralID" to 10), selfId = 10))
        // missing key → null
        assertNull(gift.argTest(mapOf("isGold" to true, "amount" to 1000), selfId = 10))
        // non-numeric amount → null
        assertNull(gift.argTest(mapOf("isGold" to true, "amount" to "x", "destGeneralID" to 22), selfId = 10))
        // non-positive destGeneralID → null
        assertNull(gift.argTest(mapOf("isGold" to true, "amount" to 1000, "destGeneralID" to 0), selfId = 10))
    }

    @Test
    fun `tribute argTest rounds to hundred and clamps without a dest`() {
        val tribute = CheHeonnap(pipeline)
        assertEquals(
            mapOf("isGold" to true, "amount" to 300),
            tribute.argTest(mapOf("isGold" to true, "amount" to 349)),
        )
        assertEquals(10000, tribute.argTest(mapOf("isGold" to true, "amount" to 50000))!!["amount"])
        assertNull(tribute.argTest(mapOf("amount" to 100)))
    }

    // --- 증여 resolve: dual move + dual log + 1:1 + exp/ded -------------------

    @Test
    fun `gift moves gold 1to1 from actor to dest with dual logs`() {
        val a = actor(gold = 5000, arg = mapOf("isGold" to true, "amount" to 1200, "destGeneralID" to 22))
        val d = destGeneral(gold = 100)
        val draft = GeneralActionDraft(a, city(), nation()).also { it.destGeneral = d }
        val ctx = GeneralActionResolveContext(draft, rng(), env, MONTH, date)
        CheJeungyeo(pipeline).resolve(ctx)

        // 1:1 move (no rate): actor -1200, dest +1200
        assertEquals(3800, draft.general.gold, "actor gold -1200")
        assertEquals(1300, draft.destGeneral!!.gold, "dest gold +1200")
        // exp70/ded100/leadership_exp+1
        assertEquals(70.0, draft.general.experience, 1e-9, "exp +70")
        assertEquals(100.0, draft.general.dedication, 1e-9, "ded +100")
        assertEquals(3, metaInt(draft.general.meta, "leadership_exp"), "leadership_exp 2→3")
        // setResultTurn writes a 증여 LastTurn carrying the normalized arg
        assertEquals("증여", draft.general.lastTurn.command)
        assertEquals(mapOf("isGold" to true, "amount" to 1200, "destGeneralID" to 22), draft.general.lastTurn.arg)

        // dest PLAIN log (no month prefix) on the dest general's bucket — actor name + literal 을
        val destLogs = ctx.logsTo(22)
        assertEquals(1, destLogs.size, "one dest log")
        assertEquals("<C>●</><Y>최강자</>에게서 금 <C>1,200</>을 증여 받았습니다.", destLogs[0])
        // actor MONTH log — dest name + literal 을 + date suffix
        val ownLogs = ctx.logs()
        assertEquals(1, ownLogs.size, "one actor log")
        assertEquals("<C>●</>${MONTH}월:<Y>최약자</>에게 금 <C>1,200</>을 증여했습니다. <1>$date</>", ownLogs[0])
    }

    @Test
    fun `gift moves rice and re-clamps to what the actor can spare above the rice minimum`() {
        // generalMinimumRice = 500; actor has 800 rice → can only spare 300 even though arg says 1000
        val a = actor(rice = 800, arg = mapOf("isGold" to false, "amount" to 1000, "destGeneralID" to 22))
        val d = destGeneral(rice = 100)
        val draft = GeneralActionDraft(a, city(), nation()).also { it.destGeneral = d }
        val ctx = GeneralActionResolveContext(draft, rng(), env, MONTH, date)
        CheJeungyeo(pipeline).resolve(ctx)

        assertEquals(500, draft.general.rice, "actor rice 800-300 (spared to the 500 minimum)")
        assertEquals(400, draft.destGeneral!!.rice, "dest rice +300")
    }

    @Test
    fun `gift with no spare resource moves nothing but still logs (increaseVarWithLimit is unconditional)`() {
        // actor rice == minimum (500): spare = valueFit(1000, 0, 500-500) = 0. The resource block uses
        // increaseVarWithLimit (NOT increaseVar), which has NO zero-short-circuit — both logs still fire
        // with amount 0 and the resource values stay put.
        val a = actor(rice = 500, arg = mapOf("isGold" to false, "amount" to 1000, "destGeneralID" to 22))
        val d = destGeneral(rice = 100)
        val draft = GeneralActionDraft(a, city(), nation()).also { it.destGeneral = d }
        val ctx = GeneralActionResolveContext(draft, rng(), env, MONTH, date)
        CheJeungyeo(pipeline).resolve(ctx)

        assertEquals(500, draft.general.rice, "actor rice unchanged")
        assertEquals(100, draft.destGeneral!!.rice, "dest rice unchanged")
        // logs fire unconditionally with amount 0 (resName 쌀)
        assertEquals("<C>●</><Y>최강자</>에게서 쌀 <C>0</>을 증여 받았습니다.", ctx.logsTo(22).single())
        assertEquals("<C>●</>${MONTH}월:<Y>최약자</>에게 쌀 <C>0</>을 증여했습니다. <1>$date</>", ctx.logs().single())
        // exp/ded/leadership_exp still applied (PHP applies them after the resource block)
        assertEquals(70.0, draft.general.experience, 1e-9)
        assertEquals(3, metaInt(draft.general.meta, "leadership_exp"))
    }

    // --- 헌납 resolve: nation-treasury add + 1:1 + exp/ded ------------------

    @Test
    fun `tribute adds gold to the nation treasury 1to1 and logs only the actor`() {
        val a = actor(gold = 5000, arg = mapOf("isGold" to true, "amount" to 1500))
            .copy(lastTurn = LastTurn(command = "헌납", arg = mapOf("isGold" to true, "amount" to 1500)))
        val n = nation(gold = 1000)
        val draft = GeneralActionDraft(a, city(), n)
        val ctx = GeneralActionResolveContext(draft, rngTribute(), env, MONTH, date)
        CheHeonnap(pipeline).resolve(ctx)

        assertEquals(3500, draft.general.gold, "actor gold -1500")
        assertEquals(2500, draft.nation!!.gold, "nation gold +1500 (1:1)")
        assertEquals(70.0, draft.general.experience, 1e-9)
        assertEquals(100.0, draft.general.dedication, 1e-9)
        assertEquals(3, metaInt(draft.general.meta, "leadership_exp"))
        // single actor MONTH log, no dest bucket
        assertEquals(1, ctx.logs().size)
        assertEquals("<C>●</>${MONTH}월:금 <C>1,500</>을 헌납했습니다. <1>$date</>", ctx.logs()[0])
        assertEquals("헌납", draft.general.lastTurn.command)
    }

    @Test
    fun `tribute adds rice to the nation treasury and floors the actor`() {
        val a = actor(rice = 5000, arg = mapOf("isGold" to false, "amount" to 2000))
            .copy(lastTurn = LastTurn(command = "헌납", arg = mapOf("isGold" to false, "amount" to 2000)))
        val n = nation(rice = 1000)
        val draft = GeneralActionDraft(a, city(), n)
        val ctx = GeneralActionResolveContext(draft, rngTribute(), env, MONTH, date)
        CheHeonnap(pipeline).resolve(ctx)

        // 헌납 re-clamps amount to valueFit(amount, 0, actor.rice) — NO minimum floor (unlike 증여)
        assertEquals(3000, draft.general.rice, "actor rice -2000")
        assertEquals(3000, draft.nation!!.rice, "nation rice +2000")
    }
}
