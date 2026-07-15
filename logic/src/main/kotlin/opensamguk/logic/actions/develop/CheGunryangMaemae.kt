package opensamguk.logic.actions.develop

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqCityTrader
import opensamguk.logic.constraints.reqGeneralGold
import opensamguk.logic.constraints.reqGeneralRice
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.actions.founding.GeneralUniqueLotteryIntent
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.clamp
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit
import kotlin.math.truncate

/**
 * che_군량매매 — PHP che_군량매매.php:22-199. The reqArg trade command: buy rice (gold→rice) or sell
 * rice (rice→gold) through a city trader at `city.trade/100` (or 1.0 when trade==null && npc>=2).
 *
 * The EXACT PHP buy/sell tax algo (research Unit 6 — NOT the TS, which diverges on the overflow tax):
 *   buyRice (gold→rice): sell=valueFit(amount*rate, max=gold); tax=sell*exchangeFee;
 *       if(sell+tax>gold){ sell *= gold/(sell+tax); tax=gold-sell; } buy=sell/rate; sell=sell+tax.
 *       general: rice += buy (increaseVar); gold = max(gold - sell, 0) (increaseVarWithLimit).
 *   sellRice (rice→gold): sell=valueFit(amount, max=rice); buy=sell*rate; tax=buy*exchangeFee; buy-=tax.
 *       general: gold += buy; rice = max(rice - sell, 0).
 *   nation.gold += (int)tax (meekrodb %i cast → truncate).
 *   exp=30, ded=50 (fixed); incStat = choiceUsingWeight(RAW stats) +1 (the ONLY RNG draw).
 *   gold/rice are integer columns — the fractional buy/sell amounts truncate toward zero at the
 *   `old + delta` combination (the D1 flush model); `increaseVar(key, 0)` is a NO-OP (LazyVarUpdater.php:73).
 *
 * argTest: amount = valueFit(round(amount, -2), 100, maxResourceActionAmount=10000); amount<=0 → invalid.
 * Constraints: ReqCityTrader(npc) + OccupiedCity(allowNeutral=TRUE) + SuppliedCity, + ReqGeneralGold(1)
 * on buyRice / ReqGeneralRice(1) on sellRice (the initWithArg full-condition gate).
 */
class CheGunryangMaemae(
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,
    /** The parsed arg (buyRice/amount). null = not yet bound (the registry default); resolve() is then a no-op. */
    private val arg: Map<String, Any?>? = null,
) : GeneralActionDefinition {
    override val key: String = "che_군량매매"
    override val name: String = "군량매매"
    override val argsSchema: Map<String, Any?> get() = mapOf("buyRice" to "bool", "amount" to "int")

    var lastUniqueLotteryIntent: GeneralUniqueLotteryIntent? = null
        private set

    override fun bindArgs(parsed: Map<String, Any?>): CheGunryangMaemae =
        CheGunryangMaemae(pipeline, maxLevel, parsed)

    fun withArg(parsed: Map<String, Any?>): CheGunryangMaemae = bindArgs(parsed)

    /**
     * PHP argTest (che_군량매매.php:26-49): buyRice must be a bool; amount must be numeric; then
     *   amount = Util::round(amount, -2)               (round to hundreds, half-away-from-zero)
     *   amount = Util::valueFit(amount, 100, 10000)    (floor 100 — so amount<=0 below is unreachable)
     *   if (amount <= 0) return false                  (DEAD given the floor of 100, but ported faithfully)
     * An invalid arg yields an empty map (PHP arg=null → command non-runnable).
     */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> {
        val buyRice = raw["buyRice"] as? Boolean ?: return emptyMap()
        val amountRaw = when (val value = raw["amount"]) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: return emptyMap()
            else -> return emptyMap()
        }
        val rounded = phpRound(amountRaw, -2)                                  // Util::round(amount, -2)
        val amount = valueFit(rounded.toDouble(), 100.0, GameConst.maxResourceActionAmount.toDouble()).toInt()
        if (amount <= 0) return emptyMap()    // PHP `if($amount <= 0) return false` — unreachable after the floor of 100
        return linkedMapOf("buyRice" to buyRice, "amount" to amount)
    }

    private fun npcType(ctx: ConstraintContext, get: (RequirementKey) -> Any?): Int =
        (get(RequirementKey.General(ctx.actorId)) as? General)?.npcType ?: 0

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val base = mutableListOf<Constraint>(
            reqCityTrader { c, view -> (view.get(RequirementKey.General(c.actorId)) as? General)?.npcType ?: 0 },
            occupiedCity(allowNeutral = true),
            suppliedCity(),
        )
        // initWithArg: full condition adds the resource gate by direction.
        val buyRice = arg?.get("buyRice") as? Boolean ?: ctx.args["buyRice"] as? Boolean
        when (buyRice) {
            true -> base.add(reqGeneralGold { _, _ -> 1 })
            false -> base.add(reqGeneralRice { _, _ -> 1 })
            null -> { /* min-condition only (no direction known yet) */ }
        }
        return base
    }

    /** min-condition preview: ReqCityTrader + OccupiedCity(allowNeutral) + SuppliedCity (no resource gate). */
    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        reqCityTrader { c, view -> (view.get(RequirementKey.General(c.actorId)) as? General)?.npcType ?: 0 },
        occupiedCity(allowNeutral = true),
        suppliedCity(),
    )

    override fun resolve(context: GeneralActionResolveContext) {
        lastUniqueLotteryIntent = null
        val parsed = arg ?: return    // no arg bound → argTest would have failed → command does not run
        resolveWith(context, parsed)
    }

    /** The real entrypoint — resolve with an explicit parsed arg (buyRice/amount). */
    fun resolveWith(context: GeneralActionResolveContext, parsed: Map<String, Any?>) {
        lastUniqueLotteryIntent = null
        val d = context.draft; val rng = context.rng
        val buyRice = parsed["buyRice"] as Boolean
        val amount = (parsed["amount"] as Number).toDouble()

        // tradeRate = city.trade/100 (or 1.0 when null && npc>=2).
        val tradeRate: Double = when (val t = d.city.trade) {
            null -> if (d.general.npcType >= 2) 1.0 else error("MustNotBeReached: null trade for non-NPC")
            else -> t / 100.0
        }

        val tax: Double
        val buyAmount: Double
        val sellAmount: Double
        val tradedGeneral: General
        if (buyRice) {
            // gold → rice
            var sell = valueFit(amount * tradeRate, null, d.general.gold.toDouble())
            var t = sell * GameConst.exchangeFee
            if (sell + t > d.general.gold) {
                sell *= d.general.gold / (sell + t)
                t = d.general.gold - sell
            }
            val buy = sell / tradeRate
            sell += t
            tax = t; buyAmount = buy; sellAmount = sell
            // general: rice += buy ; gold = max(gold - sell, 0). Int columns → truncate the combined value.
            tradedGeneral = d.general.copy(
                rice = truncate(d.general.rice + buyAmount).toInt(),
                gold = truncate(maxOf(d.general.gold - sellAmount, 0.0)).toInt(),
            )
        } else {
            // rice → gold
            val sell = valueFit(amount, null, d.general.rice.toDouble())
            var buy = sell * tradeRate
            val t = buy * GameConst.exchangeFee
            buy -= t
            tax = t; buyAmount = buy; sellAmount = sell
            tradedGeneral = d.general.copy(
                gold = truncate(d.general.gold + buyAmount).toInt(),
                rice = truncate(maxOf(d.general.rice - sellAmount, 0.0)).toInt(),
            )
        }

        // LOG (number_format no decimals = round half-up to int). buyRice vs sellRice phrasing.
        val buyText = numberFormatHalfUp(buyAmount)
        val sellText = numberFormatHalfUp(sellAmount)
        val log = if (buyRice) {
            "군량 <C>$buyText</>을 사서 자금 <C>$sellText</>을 썼습니다. <1>${context.date}</>"
        } else {
            "군량 <C>$sellText</>을 팔아 자금 <C>$buyText</>을 얻었습니다. <1>${context.date}</>"
        }
        context.addLog(log)
        d.general = tradedGeneral

        // nation.gold += (int)tax (meekrodb %i → truncate).
        d.nation = d.nation?.copy(gold = (d.nation?.gold ?: 0) + truncate(tax).toInt())

        // exp 30 / ded 50 (fixed) + the ONLY RNG draw: weighted incStat by RAW stats.
        val incStat = rng.choiceUsingWeight(linkedMapOf(
            "leadership_exp" to rawStat(d, "leadership"),
            "strength_exp" to rawStat(d, "strength"),
            "intel_exp" to rawStat(d, "intelligence")))
        d.general = d.general.copy(
            experience = d.general.experience + 30.0,
            dedication = d.general.dedication + 50.0,
            meta = withMeta(d.general.meta, incStat to metaInt(d.general.meta, incStat) + 1),
            lastTurn = LastTurn(command = name, arg = parsed),
        )
        val statResult = checkStatChange(d.general)
        d.general = statResult.general
        statResult.plainLogs.forEach { context.addPlainLog(it) }
        StaticEventHandler.handleEvent(d.general, d.destGeneral, rawClassName, emptyMap(), parsed)
        lastUniqueLotteryIntent = GeneralUniqueLotteryIntent(
            generalId = d.general.id,
            year = context.env.year,
            month = context.month,
            seedReason = lotteryActionName,
            acquireType = "아이템",
            afterTail = "setResultTurn>checkStatChange>StaticEventHandler",
        )
    }

    private fun rawStat(d: GeneralActionDraft, statName: String): Double =
        getStatValue(d.general, statName, pipeline, maxLevel,
            withInjury = false, withIActionObj = false, withStatAdjust = false, useFloor = false)

    /** PHP number_format($v) (no decimals) — half-away-from-zero to an integer, comma grouping. */
    private fun numberFormatHalfUp(value: Double): String = "%,d".format(phpRound(value))
}

fun cheGunryangMaemae(pipeline: GeneralActionPipeline, maxLevel: Int = 255) = CheGunryangMaemae(pipeline, maxLevel)
