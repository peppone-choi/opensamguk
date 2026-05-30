package opensamguk.logic.actions.trade

import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.existsDestGeneral
import opensamguk.logic.constraints.friendlyDestGeneral
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqGeneralGold
import opensamguk.logic.constraints.reqGeneralRice
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.valueFit
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * PHP `che_증여.php` (Research Unit 6) — a 1:1 (no trade rate) DUAL-entity resource move from the
 * actor to a friendly dest general, plus the fixed 통솔경험 reward.
 *
 *   - argTest (che_증여.php:28-72): keys isGold/amount/destGeneralID; amount numeric →
 *     `Util::round(amount, -2)` (round to the nearest 100, half-away-from-zero) → `valueFit(100, max)`;
 *     amount>0; isGold bool; destGeneralID int>0 AND != self. Normalized arg key order is
 *     isGold → amount → destGeneralID.
 *   - run (che_증여.php:137-181): re-clamp the amount to what the actor can spare ABOVE the resource
 *     minimum (`valueFit(amount, 0, getVar(resKey) - minimum)`), `increaseVarWithLimit(resKey, amount)`
 *     onto the dest general, `increaseVarWithLimit(resKey, -amount, 0)` off the actor (floored at 0),
 *     a PLAIN log on the DEST general's logger (`...증여 받았습니다.`) and a MONTH log on the actor
 *     (`...증여했습니다. <1>date</>`), then `addExperience(70)` / `addDedication(100)` /
 *     `increaseVar('leadership_exp', 1)` / `setResultTurn` / `checkStatChange`. The trailing
 *     unique-item lottery rides a SEPARATE rng (not drawn here).
 *
 * The actor/dest names come from `meta["name"]` (the logic [General] has no name column; the daemon
 * overlay carries it on meta). The literal 을 in the log bodies is a fixed PHP string (NOT josa-picked).
 */
class CheJeungyeo(private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {
    override val key: String get() = "che_증여"
    override val name: String get() = "증여"
    override val category: String get() = "자원교역"

    override val argsSchema: Map<String, Any?> get() =
        mapOf("isGold" to "bool", "amount" to "int", "destGeneralID" to "int")

    fun getCommandDetailTitle(): String = "$name(통솔경험)"

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val isGold = ctx.args["isGold"] == true
        val cons = mutableListOf(
            notBeNeutral(), occupiedCity(), suppliedCity(),
            existsDestGeneral(), friendlyDestGeneral(),
        )
        // che_증여.php:101-105 — ReqGeneralGold(minGold) when isGold, else ReqGeneralRice(minRice).
        if (isGold) cons += reqGeneralGold { _, _ -> GameConst.generalMinimumGold }
        else cons += reqGeneralRice { _, _ -> GameConst.generalMinimumRice }
        return cons
    }

    // che_증여.php:82-86 — min-condition is the cheap NotBeNeutral/OccupiedCity/SuppliedCity preview.
    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> =
        listOf(notBeNeutral(), occupiedCity(), suppliedCity())

    /**
     * che_증여.php:28-72 `argTest`. Returns the normalized arg map (isGold→amount→destGeneralID) or
     * null when invalid. `selfId` is the actor general id (PHP `$this->generalObj->getID()`) — the
     * dest-is-self rejection.
     */
    fun argTest(raw: Map<String, Any?>, selfId: Int): Map<String, Any?>? {
        if (!raw.containsKey("isGold") || !raw.containsKey("amount") || !raw.containsKey("destGeneralID")) return null
        val isGold = raw["isGold"] as? Boolean ?: return null
        val amountRaw = raw["amount"] as? Number ?: return null
        val destGeneralID = (raw["destGeneralID"] as? Number)?.let {
            if (it.toDouble() != it.toLong().toDouble()) return null else it.toInt()
        } ?: return null

        var amount = roundToHundred(amountRaw.toDouble())
        amount = valueFit(amount.toDouble(), 100.0, GameConst.maxResourceActionAmount.toDouble()).toInt()
        if (amount <= 0) return null
        if (destGeneralID <= 0) return null
        if (destGeneralID == selfId) return null

        return linkedMapOf("isGold" to isGold, "amount" to amount, "destGeneralID" to destGeneralID)
    }

    // Reservation-time normalize (interface): the self-reject needs the running actor id, unknown at
    // reservation — re-checked at run via the constraint stack. selfId=-1 makes the self-branch inert.
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw, selfId = -1) ?: emptyMap()

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        @Suppress("UNCHECKED_CAST")
        val arg = (d.general.lastTurn.arg ?: emptyMap()) as Map<String, Any?>
        val isGold = arg["isGold"] == true
        val amountArg = (arg["amount"] as? Number)?.toInt() ?: 0
        val resName = if (isGold) "금" else "쌀"
        val minimum = if (isGold) GameConst.generalMinimumGold else GameConst.generalMinimumRice

        val dest = d.destGeneral ?: error("che_증여 resolve: destGeneral not loaded")

        // che_증여.php:154 — re-clamp to what the actor can spare above the resource minimum.
        val actorRes = if (isGold) d.general.gold else d.general.rice
        val amount = valueFit(amountArg.toDouble(), 0.0, (actorRes - minimum).toDouble()).toInt()
        val amountText = numberFormat(amount)

        // che_증여.php:159-160 — increaseVarWithLimit (NO zero short-circuit): dest +amount, actor -amount floored at 0.
        d.destGeneral = addResource(dest, isGold, amount, min = null)
        d.general = addResource(d.general, isGold, -amount, min = 0)

        // che_증여.php:162-163 — DEST PLAIN log (<C>●</>{body}) + actor MONTH log. Both fire unconditionally.
        val actorName = nameOf(d.general)
        val destName = nameOf(dest)
        context.addLogTo(dest.id, "<C>●</><Y>$actorName</>에게서 $resName <C>$amountText</>을 증여 받았습니다.")
        context.addLog("<Y>$destName</>에게 $resName <C>$amountText</>을 증여했습니다. <1>${context.date}</>")

        // che_증여.php:165-170 — fixed reward.
        applyTributeReward(context, pipeline)

        // che_증여.php:172 — setResultTurn(new LastTurn(getName(), arg)).
        d.general = d.general.copy(lastTurn = LastTurn(command = name, arg = arg))

        // che_증여.php:173 — checkStatChange (PLAIN level logs).
        val sc = checkStatChange(d.general)
        d.general = sc.general
        for (line in sc.plainLogs) context.addPlainLog(line)
    }
}

// ---- shared trade-family helpers (same package) -----------------------------

/** PHP `Util::round($v, -2)` = intval(round($v, -2)) — round to the nearest 100, half-away-from-zero. */
internal fun roundToHundred(value: Double): Int =
    BigDecimal.valueOf(value).setScale(-2, RoundingMode.HALF_UP).toInt()

/** The general's display name (logic [General] has no name column; rides meta["name"]). */
internal fun nameOf(g: General): String = g.meta["name"] as? String ?: ""

/** increaseVarWithLimit on gold|rice: raw + delta, optional lower clamp (PHP LazyVarUpdater.php:80-92). */
internal fun addResource(g: General, isGold: Boolean, delta: Int, min: Int?): General {
    val next = (if (isGold) g.gold else g.rice) + delta
    val clamped = if (min != null && next < min) min else next
    return if (isGold) g.copy(gold = clamped) else g.copy(rice = clamped)
}

/**
 * The fixed 통솔경험 reward shared by 증여/헌납 (che_증여.php:165-170 / che_헌납.php:140-145):
 *   addExperience(70) → addDedication(100) → increaseVar('leadership_exp', 1).
 * addExperience/addDedication fold through onCalcStat (identity for a default general) and emit any
 * PLAIN 레벨업/승급 logs. leadership_exp is a raw +1 on meta.
 */
internal fun applyTributeReward(context: GeneralActionResolveContext, pipeline: GeneralActionPipeline) {
    val d = context.draft
    val expRes = addExperience(d.general, 70.0, pipeline)
    d.general = expRes.general
    expRes.plainLog?.let { context.addPlainLog(it) }

    val dedRes = addDedication(d.general, 100.0, pipeline)
    d.general = dedRes.general
    dedRes.plainLog?.let { context.addPlainLog(it) }

    d.general = d.general.copy(
        meta = opensamguk.logic.domain.withMeta(
            d.general.meta, "leadership_exp" to metaInt(d.general.meta, "leadership_exp") + 1,
        ),
    )
}
