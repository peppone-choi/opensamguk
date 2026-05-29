package opensamguk.logic.actions.nation

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.existsDestGeneral
import opensamguk.logic.constraints.friendlyDestGeneral
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqNationGold
import opensamguk.logic.constraints.reqNationRice
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit

/**
 * che_포상 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_포상.php`.
 *
 * Chief rewards a FRIENDLY dest general with nation gold/rice. The DOUBLE-CLAMP:
 *  1. argTest (parseArgs): `amount = Util::round(amount, -2)` (round to nearest 100, half-away) then
 *     `Util::valueFit(amount, 100, maxResourceActionAmount=10000)` (che_포상.php:47-48).
 *  2. run() RE-clamp: `amount = Util::valueFit(amount, 0, nation[resKey] - reserve)`, reserve =
 *     basegold(0)/baserice(2000) (che_포상.php:158-162).
 * Then: dest general `increaseVar(resKey, amount)`; nation `[resKey] -= amount`. ZERO exp/ded; NO
 * inheritance. self-target → AlwaysFail '본인입니다'.
 *
 * Apply ORDER (che_포상.php:177-180): setResultTurn → handleEvent → applyDB(general) → applyDB(destGeneral).
 */
fun chePosang(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): NationCommand = object : NationCommand() {
    override val key: String get() = "che_포상"
    override val name: String get() = "포상"
    override val argsSchema: Map<String, Any?> get() =
        mapOf("isGold" to "bool", "amount" to "int", "destGeneralID" to "int")

    override fun getPreReqTurn(): Int = 0

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), occupiedCity(), beChief(), suppliedCity(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        if ((ctx.args["destGeneralID"] as? Number)?.toInt() == ctx.actorId) {
            return listOf(alwaysFail("본인입니다"))
        }
        val isGold = ctx.args["isGold"] as? Boolean ?: false
        val base = listOf(
            notBeNeutral(), occupiedCity(), beChief(), suppliedCity(),
            existsDestGeneral(), friendlyDestGeneral(),
        )
        // che_포상.php:104-108 — ReqNationGold(1+basegold) / ReqNationRice(1+baserice).
        return if (isGold) base + reqNationGold { _, _ -> 1 + GameConst.basegold }
        else base + reqNationRice { _, _ -> 1 + GameConst.baserice }
    }

    /** che_포상.php:47-48 — round(-2) then valueFit(100, 10000). */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> {
        val rawAmount = (raw["amount"] as? Number)?.toDouble() ?: 0.0
        val rounded = phpRound(rawAmount / 100.0) * 100                 // Util::round($amount, -2)
        val clamped = valueFit(rounded.toDouble(), 100.0, GameConst.maxResourceActionAmount.toDouble()).toInt()
        return linkedMapOf(
            "isGold" to (raw["isGold"] as? Boolean ?: false),
            "amount" to clamped,
            "destGeneralID" to (raw["destGeneralID"] as? Number)?.toInt(),
        )
    }

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val nation = d.nation ?: return
        val destGeneral = d.destGeneral ?: return
        val isGold = context.args["isGold"] as? Boolean ?: false
        val argAmount = (context.args["amount"] as? Number)?.toInt() ?: return

        // run() re-clamp: valueFit(amount, 0, nation[resKey] - reserve), reserve = basegold/baserice.
        val reserve = if (isGold) GameConst.basegold else GameConst.baserice
        val balance = if (isGold) nation.gold else nation.rice
        val amount = valueFit(argAmount.toDouble(), 0.0, (balance - reserve).toDouble()).toInt()
        val amountText = numberFormat(amount)
        val resName = if (isGold) "금" else "쌀"

        // dest general resKey += amount; nation resKey -= amount (che_포상.php:167-170)
        d.destGeneral = if (isGold) destGeneral.copy(gold = destGeneral.gold + amount)
        else destGeneral.copy(rice = destGeneral.rice + amount)
        d.nation = if (isGold) nation.copy(gold = nation.gold - amount)
        else nation.copy(rice = nation.rice - amount)

        // dest PLAIN log (che_포상.php:174) + actor log (:175)
        val josaUl = JosaUtil.pick(amountText, "을")
        context.addPlainLogTo(destGeneral.id, "$resName <C>$amountText</>$josaUl 포상으로 받았습니다.")
        context.addLog(
            "<Y>${context.destGeneralName}</>에게 $resName <C>$amountText</>$josaUl 수여했습니다. <1>${context.date}</>")

        // ZERO exp/ded; NO inheritance.
    }
}
