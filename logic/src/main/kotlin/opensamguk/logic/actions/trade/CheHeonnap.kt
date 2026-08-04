package opensamguk.logic.actions.trade

import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.CommandFieldSpec
import opensamguk.logic.actions.CommandFormSpec
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqGeneralGold
import opensamguk.logic.constraints.reqGeneralRice
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.valueFit

/**
 * PHP `che_헌납.php` (Research Unit 6) — a 1:1 move of the actor's resource INTO the nation treasury,
 * plus the same fixed 통솔경험 reward as 증여. No dest general, no trade rate.
 *
 *   - argTest (che_헌납.php:26-52): keys isGold/amount; amount numeric → `Util::round(amount, -2)` →
 *     `valueFit(100, max)`; isGold bool. (No `amount<=0` guard and no destGeneralID — unlike 증여.)
 *     Normalized arg key order is isGold → amount.
 *   - run (che_헌납.php:111-155): re-clamp the amount to the actor's FULL balance
 *     (`valueFit(amount, 0, getVar(resKey))` — NO minimum floor, unlike 증여), add it to the nation
 *     treasury (`UPDATE nation SET {resKey} = {resKey} + amount`), subtract it off the actor floored at
 *     0, push only the actor MONTH log (`...헌납했습니다. <1>date</>`), then `addExperience(70)` /
 *     `addDedication(100)` / `increaseVar('leadership_exp', 1)` / `setResultTurn` / `checkStatChange`.
 */
class CheHeonnap(private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {
    override val key: String get() = "che_헌납"
    override val name: String get() = "헌납"
    override val category: String get() = "자원교역"

    override val argsSchema: Map<String, Any?> get() = mapOf("isGold" to "bool", "amount" to "int")
    override val formSpec: CommandFormSpec get() = CommandFormSpec(
        listOf(
            CommandFieldSpec("isGold", "bool", "toggle", "resourceKinds"),
            CommandFieldSpec("amount", "int", "amount", min = 100, max = GameConst.maxResourceActionAmount),
        ),
    )

    fun getCommandDetailTitle(): String = "$name(통솔경험)"

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val isGold = ctx.args["isGold"] == true
        val cons = mutableListOf(notBeNeutral(), occupiedCity(), suppliedCity())
        // che_헌납.php:76-80 — ReqGeneralGold(minGold) when isGold, else ReqGeneralRice(minRice).
        if (isGold) cons += reqGeneralGold { _, _ -> GameConst.generalMinimumGold }
        else cons += reqGeneralRice { _, _ -> GameConst.generalMinimumRice }
        return cons
    }

    // che_헌납.php:62-66 — min-condition is NotBeNeutral/OccupiedCity/SuppliedCity.
    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> =
        listOf(notBeNeutral(), occupiedCity(), suppliedCity())

    /** che_헌납.php:26-52 `argTest`. Returns the normalized arg (isGold→amount) or null when invalid. */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        if (!raw.containsKey("isGold") || !raw.containsKey("amount")) return null
        val isGold = raw["isGold"] as? Boolean ?: return null
        val amountRaw = raw["amount"] as? Number ?: return null
        var amount = roundToHundred(amountRaw.toDouble())
        amount = valueFit(amount.toDouble(), 100.0, GameConst.maxResourceActionAmount.toDouble()).toInt()
        return linkedMapOf("isGold" to isGold, "amount" to amount)
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val arg = context.args
        val isGold = arg["isGold"] == true
        val amountArg = (arg["amount"] as? Number)?.toInt() ?: 0
        val resName = if (isGold) "금" else "쌀"

        val nation = d.nation ?: error("che_헌납 resolve: nation not loaded")

        // che_헌납.php:127 — re-clamp to the actor's FULL balance (no minimum floor).
        val actorRes = if (isGold) d.general.gold else d.general.rice
        val amount = valueFit(amountArg.toDouble(), 0.0, actorRes.toDouble()).toInt()
        val amountText = numberFormat(amount)

        // che_헌납.php:132-136 — nation treasury += amount; actor -= amount floored at 0.
        d.nation = if (isGold) nation.copy(gold = nation.gold + amount) else nation.copy(rice = nation.rice + amount)
        d.general = addResource(d.general, isGold, -amount, min = 0)

        // che_헌납.php:138 — actor MONTH log only.
        context.addLog("$resName <C>$amountText</>을 헌납했습니다. <1>${context.date}</>")

        // che_헌납.php:140-145 — fixed 통솔경험 reward (shared with 증여).
        applyTributeReward(context, pipeline)

        // che_헌납.php:147 — setResultTurn(new LastTurn(getName(), arg)).
        d.general = d.general.copy(lastTurn = LastTurn(command = name, arg = arg))

        // che_헌납.php:148 — checkStatChange (PLAIN level logs).
        val sc = opensamguk.logic.domestic.checkStatChange(d.general)
        d.general = sc.general
        for (line in sc.plainLogs) context.addPlainLog(line)
    }
}
