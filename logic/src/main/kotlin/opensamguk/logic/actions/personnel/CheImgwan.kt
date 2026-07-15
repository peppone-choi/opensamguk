package opensamguk.logic.actions.personnel

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowJoinAction
import opensamguk.logic.constraints.allowJoinDestNation
import opensamguk.logic.constraints.beNeutral
import opensamguk.logic.constraints.noPenalty
import opensamguk.logic.actions.founding.GeneralUniqueLotteryIntent
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * The JOIN effect template — faithful port of `che_임관.php` / `che_장수대상임관.php` run().
 *
 * Both commands transition a 재야 general into a destination nation as a 1급 (officer_level 1) member,
 * award the join exp, bump the destination nation's gennum, and emit the join action + global-action logs.
 * They differ ONLY in: (a) which secondary target seeds the destination city (the dest LORD's city for
 * 임관 with `setDestNation`, vs the dest GENERAL's city for 장수대상임관), (b) whether `troop` is reset
 * (임관 does; 장수대상임관 does NOT), and (c) the constraint pack (임관 adds NoPenalty(NoChosenAssignment)).
 *
 * `belong`/`officer_city` are NOT V1 general columns — they ride the `meta` jsonb (GeneralBase var
 * allow-list; the V1 baseline `general` table has no belong/officer_city columns). The
 * `increaseInheritancePoint(active_action, 1)` call is the succession-subsystem rank-var seam (OQ7/P6) —
 * NO state write here, mirroring CommerceInvestment's documented no-inheritance stance. There is NO
 * gameplay RNG (only the trailing unique-item lottery, which rides a SEPARATE "unique" rng — F-RNG seam).
 */
abstract class JoinCommand(
    private val pipeline: GeneralActionPipeline,
) : GeneralActionDefinition {

    override val category: String get() = "인사"

    var lastUniqueLotteryIntent: GeneralUniqueLotteryIntent? = null
        private set

    /** che_임관 resets `troop`; che_장수대상임관 does NOT (the PHP run() divergence). */
    protected abstract val resetsTroop: Boolean

    /** PHP argTest-normalized payload persisted by setResultTurn for the concrete command. */
    protected abstract fun lastTurnArg(
        draft: opensamguk.logic.actions.GeneralActionDraft,
        destNationId: Int,
    ): Map<String, Any?>

    /**
     * The destination CITY id the general moves to: che_임관 uses the dest LORD's city (the
     * `officer_level = 12` general's city in the dest nation), surfaced as `draft.destCity`; che_장수대상임관
     * uses `draft.destGeneral`'s city. Both are preloaded into the draft by the adapter.
     */
    protected fun destinationCityId(
        draft: opensamguk.logic.actions.GeneralActionDraft,
    ): Int = draft.destGeneral?.cityId ?: draft.destCity?.id
        ?: error("JoinCommand requires a destGeneral or destCity to resolve the join city")

    override fun resolve(context: GeneralActionResolveContext) {
        lastUniqueLotteryIntent = null
        val d = context.draft
        val destNation = d.destNation ?: error("JoinCommand requires a destNation")
        val destNationId = destNation.id
        val destNationName = destNation.name

        context.addLog("<D>$destNationName</>에 임관했습니다. <1>${context.date}</>")
        context.addGeneralHistoryLog("<D><b>$destNationName</b></>에 임관")
        val generalName = context.generalName.ifEmpty { (d.general.meta["name"] as? String) ?: "" }
        val josaYi = JosaUtil.pick(generalName, "이")
        context.addGlobalActionLog("<Y>$generalName</>$josaYi <D><b>$destNationName</b></>에 <S>임관</>했습니다.")

        // exp 700 if dest nation is below the early-game gen limit, else 100.
        val exp = if (destNation.gennum < GameConst.initialNationGenLimit) 700.0 else 100.0

        // JOIN transition (PHP setVar block): nation/officer_level/officer_city/belong/(troop)/city.
        val joinedCity = destinationCityId(d)
        var g = d.general.copy(
            nationId = destNationId,
            officerLevel = 1,
            cityId = joinedCity,
            meta = withMeta(d.general.meta, "officer_city" to 0, "belong" to 1),
        )
        if (resetsTroop) {
            g = g.copy(troop = 0)
        }

        // dest nation gennum + 1 (the SECOND-entity write).
        d.destNation = destNation.copy(
            gennum = destNation.gennum + 1,
            meta = withMeta(destNation.meta, "gennum" to destNation.gennum + 1),
        )

        // increaseInheritancePoint(active_action, 1) — succession rank-var seam (OQ7/P6); no write here.

        // addExperience(exp) — raw increaseVar (no per-add round; truncated to int only at flush).
        g = g.copy(experience = g.experience + exp)
        val statChange = checkStatChange(
            g.copy(lastTurn = LastTurn(command = name, arg = lastTurnArg(d, destNationId))),
        )
        d.general = statChange.general
        for (line in statChange.plainLogs) context.addPlainLog(line)
        StaticEventHandler.handleEvent(d.general, null, key, emptyMap(), context.args)
        lastUniqueLotteryIntent = GeneralUniqueLotteryIntent(
            generalId = d.general.id,
            year = context.env.year,
            month = context.month,
            seedReason = lotteryActionName,
            acquireType = "아이템",
            afterTail = "setResultTurn>checkStatChange>StaticEventHandler",
        )
    }

    protected fun joinConstraints(npc: (ConstraintContext, opensamguk.logic.constraints.StateView) -> Int):
        List<Constraint> = listOf(
        beNeutral(),
        existsDestNation(),
        allowJoinDestNation(npc),
        allowJoinAction(),
    )

    companion object {
        /** ExistsDestNation faithful inline — pass when destNation id != 0; else `없는 국가입니다.`. */
        fun existsDestNation() = object : Constraint {
            override val name = "ExistsDestNation"
            override fun requires(ctx: ConstraintContext) =
                listOf(opensamguk.logic.constraints.RequirementKey.DestNation(ctx.destNationId ?: 0))
            override fun test(ctx: ConstraintContext, view: opensamguk.logic.constraints.StateView):
                opensamguk.logic.constraints.ConstraintResult {
                val n = ctx.destNationId?.let { view.get(opensamguk.logic.constraints.RequirementKey.DestNation(it)) as? Nation }
                    ?: return opensamguk.logic.constraints.ConstraintResult.Unknown(requires(ctx))
                return if (n.id != 0) opensamguk.logic.constraints.ConstraintResult.Allow
                else opensamguk.logic.constraints.ConstraintResult.Deny("없는 국가입니다.")
            }
        }
    }
}

/**
 * che_임관 — JOIN a specified nation (arg destNationID). Resets `troop`, joins the dest LORD's city,
 * and adds the NoPenalty(NoChosenAssignment) constraint (`che_임관.php:72/99`). PenaltyKey
 * `NoChosenAssignment` rides the general `penalty` map.
 */
class CheImgwan(pipeline: GeneralActionPipeline) : JoinCommand(pipeline) {
    override val key: String get() = "che_임관"
    override val name: String get() = "임관"
    override val resetsTroop: Boolean get() = true
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destNationID" to "int")
    override fun lastTurnArg(
        draft: opensamguk.logic.actions.GeneralActionDraft,
        destNationId: Int,
    ): Map<String, Any?> = linkedMapOf("destNationID" to destNationId)

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> =
        joinConstraints { c, _ ->
            (c.args["actorNpcType"] as? Number)?.toInt() ?: 2   // PHP general['npc'] ?? 2
        } + noPenalty(PENALTY_NO_CHOSEN_ASSIGNMENT)

    companion object {
        /** PenaltyKey::NoChosenAssignment — the penalty-map key blocking self-directed 임관. */
        const val PENALTY_NO_CHOSEN_ASSIGNMENT = "NoChosenAssignment"
    }
}

/**
 * che_장수대상임관 — JOIN the nation of a target general (arg destGeneralID), following that general into
 * their CITY. Does NOT reset `troop`, and omits the NoPenalty constraint (`che_장수대상임관.php:69-73/94-100`).
 */
class CheJangsuDaesangImgwan(pipeline: GeneralActionPipeline) : JoinCommand(pipeline) {
    override val key: String get() = "che_장수대상임관"
    override val name: String get() = "장수를 따라 임관"
    override val resetsTroop: Boolean get() = false
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destGeneralID" to "int")
    override fun lastTurnArg(
        draft: opensamguk.logic.actions.GeneralActionDraft,
        destNationId: Int,
    ): Map<String, Any?> = linkedMapOf(
        "destGeneralID" to (draft.destGeneral?.id ?: error("che_장수대상임관 requires a destGeneral")),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> =
        joinConstraints { c, _ -> (c.args["actorNpcType"] as? Number)?.toInt() ?: 2 }
}
