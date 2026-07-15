package opensamguk.logic.actions.develop

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.clamp
import kotlin.math.truncate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DV5 — che_군량매매 (PHP che_군량매매.php:22-199), the reqArg trade command.
 *
 * The EXACT PHP buyRice/sellRice tax algo (research Unit 6 — NOT the TS, which diverges on the
 * overflow tax):
 *   buyRice (gold→rice): sell=clamp(amount*rate, max=gold); tax=sell*0.01;
 *       if(sell+tax>gold){ sell *= gold/(sell+tax); tax=gold-sell; } buy=sell/rate; sell=sell+tax.
 *   sellRice (rice→gold): sell=clamp(amount, max=rice); buy=sell*rate; tax=buy*0.01; buy=buy-tax.
 *   rate = city.trade/100 (or 1.0 if trade==null && npc>=2).
 *   argTest: amount = valueFit(round(amount,-2), 100, maxResourceActionAmount=10000).
 *   exp=30, ded=50 (fixed); incStat = choiceUsingWeight(RAW stats) +1; nation.gold += (int)tax.
 *   Constraints: ReqCityTrader, OccupiedCity(allowNeutral=true), SuppliedCity (+ReqGeneralGold/Rice(1) on full).
 */
class CheGunryangMaemaeTest {

    private val pipeline = GeneralActionPipeline()
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val MONTH = 3
    private val date = "12:34"
    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)

    private fun freshRng() = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 190, MONTH, 42, "che_군량매매")))

    private fun general(gold: Int = 5000, rice: Int = 5000) = General(
        id = 42, nationId = 1, cityId = 7,
        leadership = 70, strength = 75, intel = 80,
        injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = gold, rice = rice,
        meta = linkedMapOf("leadership_exp" to 1, "strength_exp" to 2, "intel_exp" to 3),
    )

    private fun city(trade: Int? = 100) = City(
        id = 7, nationId = 1, level = 5,
        commerce = 1000, commerceMax = 20000,
        agriculture = 1000, agricultureMax = 20000,
        supplyState = 1, frontState = 0,
        trust = 50.0, trade = trade, meta = linkedMapOf(),
    )

    private fun nation() = Nation(id = 1, level = 2, capitalCityId = 99, gold = 10000, rice = 10000)
    private fun action() = cheGunryangMaemae(pipeline)

    @AfterTest
    fun clearStaticHandlers() = StaticEventHandler.clear()

    // ---------- argTest ----------
    @Test
    fun `argTest double-rounds amount — round to -2 then clamp 100 to 10000`() {
        val a = action()
        // 1234 → round(,-2)=1200 → valueFit(1200,100,10000)=1200
        assertEquals(mapOf("buyRice" to true, "amount" to 1200),
            a.parseArgs(mapOf("buyRice" to true, "amount" to 1234)))
        // 49 → round(,-2)=0 → valueFit(0,100,10000)=100 (floor of 100; the `<=0` check is unreachable in PHP)
        assertEquals(mapOf("buyRice" to true, "amount" to 100),
            a.parseArgs(mapOf("buyRice" to true, "amount" to 49)),
            "amount rounds to 0 then floors to 100")
        // 50 → round(,-2)=100 → valueFit=100
        assertEquals(mapOf("buyRice" to false, "amount" to 100),
            a.parseArgs(mapOf("buyRice" to false, "amount" to 50)))
        // 99999 → round=100000 → clamp 10000
        assertEquals(mapOf("buyRice" to true, "amount" to 10000),
            a.parseArgs(mapOf("buyRice" to true, "amount" to 99999)))
    }

    // ---------- ReqCityTrader ----------
    @Test
    fun `ReqCityTrader passes with a trade rate, denies a no-trade city for a non-NPC`() {
        val view = object : StateView {
            override fun has(req: RequirementKey) = true
            override fun get(req: RequirementKey): Any? = when (req) {
                is RequirementKey.General -> general()
                is RequirementKey.City -> cityForTest
                is RequirementKey.Nation -> nation()
                else -> null
            }
        }
        val ctx = ConstraintContext(actorId = 42, cityId = 7, nationId = 1, mode = ConstraintMode.FULL)
        val trader = action().buildConstraints(ctx).first { it.name == "ReqCityTrader" }

        cityForTest = city(trade = 100)
        assertEquals(ConstraintResult.Allow, trader.test(ctx, view), "has trade → pass")

        cityForTest = city(trade = null)
        val denied = trader.test(ctx, view)
        assertTrue(denied is ConstraintResult.Deny && denied.reason == "도시에 상인이 없습니다.",
            "no-trade non-NPC city denied; got $denied")
    }
    private var cityForTest: City = city(100)

    @Test
    fun `constraints — ReqCityTrader + OccupiedCity(allowNeutral) + SuppliedCity + gold gate on buy`() {
        val ctx = ConstraintContext(actorId = 42, cityId = 7, nationId = 1, mode = ConstraintMode.FULL,
            args = mapOf("buyRice" to true, "amount" to 1000))
        val names = action().buildConstraints(ctx).map { it.name }
        assertTrue(names.contains("ReqCityTrader"))
        assertTrue(names.contains("OccupiedCity"))
        assertTrue(names.contains("SuppliedCity"))
        assertTrue(names.contains("ReqGeneralGold"), "buyRice → ReqGeneralGold(1) gate")
    }

    // ---------- buyRice tax algo ----------
    @Test
    fun `buyRice gold to rice — exact float tax order, no overflow`() {
        val amount = 1000; val rate = 100.0 / 100.0  // trade 100 → rate 1.0
        val g = general(gold = 5000, rice = 5000)
        // PHP: sell=clamp(1000*1.0, max=5000)=1000; tax=1000*0.01=10; (1010>5000? no); buy=1000/1.0=1000; sell=1010.
        var sell = clamp(amount * rate, null, g.gold.toDouble()); var tax = sell * GameConst.exchangeFee
        if (sell + tax > g.gold) { sell *= g.gold / (sell + tax); tax = g.gold - sell }
        val buy = sell / rate; sell += tax
        val expGold = truncate(maxOf(g.gold - sell, 0.0)).toInt()
        val expRice = truncate(g.rice + buy).toInt()
        val expTax = truncate(tax).toInt()

        val draft = GeneralActionDraft(g, city(100), nation())
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        ctx_run(draft, ctx, buyRice = true, amount = amount)

        assertEquals(expGold, draft.general.gold, "gold -= sell(+tax)")
        assertEquals(expRice, draft.general.rice, "rice += buy")
        assertEquals(10000 + expTax, draft.nation!!.gold, "nation.gold += (int)tax")
    }

    @Test
    fun `buyRice overflow tax — sell rescaled when sell+tax exceeds gold`() {
        // gold barely covers amount*rate so the overflow branch fires.
        val amount = 1000; val rate = 1.0
        val g = general(gold = 1000, rice = 0)
        var sell = clamp(amount * rate, null, g.gold.toDouble())  // 1000
        var tax = sell * GameConst.exchangeFee                    // 10
        // 1000+10 > 1000 → overflow: sell *= 1000/1010; tax = 1000 - sell
        if (sell + tax > g.gold) { sell *= g.gold / (sell + tax); tax = g.gold - sell }
        val buy = sell / rate; sell += tax
        val expGold = truncate(maxOf(g.gold - sell, 0.0)).toInt()
        val expTax = truncate(tax).toInt()

        val draft = GeneralActionDraft(g, city(100), nation())
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        ctx_run(draft, ctx, buyRice = true, amount = amount)

        // after the overflow rescale sell+tax == gold exactly → gold floored to 0 (truncate)
        assertEquals(expGold, draft.general.gold, "overflow: gold -> ~0")
        assertEquals(10000 + expTax, draft.nation!!.gold, "tax = gold - rescaledSell")
        assertEquals(truncate(0.0 + buy).toInt(), draft.general.rice, "rice += rescaled buy")
    }

    // ---------- sellRice ----------
    @Test
    fun `sellRice rice to gold — buy minus tax`() {
        val amount = 1000; val rate = 1.0
        val g = general(gold = 0, rice = 5000)
        val sell = clamp(amount.toDouble(), null, g.rice.toDouble())  // 1000
        var buy = sell * rate                                          // 1000
        val tax = buy * GameConst.exchangeFee                         // 10
        buy -= tax                                                    // 990
        val expRice = truncate(maxOf(g.rice - sell, 0.0)).toInt()
        val expGold = truncate(g.gold + buy).toInt()
        val expTax = truncate(tax).toInt()

        val draft = GeneralActionDraft(g, city(100), nation())
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        ctx_run(draft, ctx, buyRice = false, amount = amount)

        assertEquals(expRice, draft.general.rice, "rice -= sell")
        assertEquals(expGold, draft.general.gold, "gold += buy-tax")
        assertEquals(10000 + expTax, draft.nation!!.gold, "nation.gold += (int)tax")
    }

    @Test
    fun `fixed exp 30 ded 50 and weighted incStat bump`() {
        val draft = GeneralActionDraft(general(), city(100), nation())
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        ctx_run(draft, ctx, buyRice = false, amount = 1000)
        assertEquals(30.0, draft.general.experience, 1e-12, "fixed exp 30")
        assertEquals(50.0, draft.general.dedication, 1e-12, "fixed ded 50")
        assertEquals("군량매매", draft.general.lastTurn.command, "setResultTurn after exp/ded/stat tail")
        assertEquals(mapOf("buyRice" to false, "amount" to 1000), draft.general.lastTurn.arg)
        // exactly one incStat bumped (the others unchanged)
        val before = general()
        val bumped = listOf("leadership_exp", "strength_exp", "intel_exp").count {
            metaInt(draft.general.meta, it) == metaInt(before.meta, it) + 1
        }
        assertEquals(1, bumped, "exactly one weighted incStat bumped by 1")
    }

    @Test
    fun `tail publishes static event after lastTurn and then unique lottery intent`() {
        val observed = mutableListOf<String>()
        StaticEventHandler.register("che_군량매매") { general, _, _, params ->
            observed += "${general.lastTurn.command}:${params["buyRice"]}"
        }

        val command = action()
        val draft = GeneralActionDraft(general(), city(100), nation())
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        command.resolveWith(ctx, command.parseArgs(mapOf("buyRice" to false, "amount" to 1000)))

        assertEquals(listOf("군량매매:false"), observed)
        assertEquals("군량매매", command.lastUniqueLotteryIntent?.seedReason)
        assertEquals("아이템", command.lastUniqueLotteryIntent?.acquireType)
        assertEquals("setResultTurn>checkStatChange>StaticEventHandler", command.lastUniqueLotteryIntent?.afterTail)
    }

    @Test
    fun `null trade with NPC general uses rate 1`() {
        val npc = general().copy(npcType = 2, gold = 5000, rice = 5000)
        val draft = GeneralActionDraft(npc, city(trade = null), nation())
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        ctx_run(draft, ctx, buyRice = false, amount = 1000)
        // rate 1.0 → buy ~ 1000 - tax; gold increases
        assertTrue(draft.general.gold > 5000, "NPC null-trade uses rate 1.0; gold=${draft.general.gold}")
    }

    @Test
    fun `determinism`() {
        val a = GeneralActionDraft(general(), city(100), nation())
        ctx_run(a, GeneralActionResolveContext(a, freshRng(), env, MONTH, date), false, 1000)
        val b = GeneralActionDraft(general(), city(100), nation())
        ctx_run(b, GeneralActionResolveContext(b, freshRng(), env, MONTH, date), false, 1000)
        assertEquals(a.general.gold, b.general.gold)
        assertEquals(a.general.rice, b.general.rice)
        assertEquals(a.nation!!.gold, b.nation!!.gold)
    }

    /** helper: parse the arg, then resolve. */
    private fun ctx_run(draft: GeneralActionDraft, ctx: GeneralActionResolveContext, buyRice: Boolean, amount: Int) {
        // arg is carried via the context's draft path in production; here we set it through the action's
        // resolve which reads the parsed arg from the context. The resolver reads ctx.args via the draft —
        // see CheGunryangMaemae.resolve(arg).
        action().resolveWith(ctx, action().parseArgs(mapOf("buyRice" to buyRice, "amount" to amount)))
    }
}
