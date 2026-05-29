package opensamguk.logic.actions.personnel

import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView
import opensamguk.logic.domain.General
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.rebirth
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_은퇴 — faithful port of `che_은퇴.php`.
 *
 *   - reqAge = 60: ReqGeneralValue('age','나이','>=',60,'나이가 60세 이상이어야 합니다.'). Since `age` rides
 *     meta (not a `General` field nor in the C-PURE `generalNumField` resolver), the constraint is the
 *     age-on-meta form, keeping the PHP constraint identity name "ReqGeneralValue" + reason.
 *   - getPreReqTurn() = 1 → a 2-turn command ([preReqTurn]).
 *   - run(): if env.isunited == 0 → CheckHall(generalID) BEFORE rebirth (succession subsystem — guarded
 *     STUB, no-op in P2); then `General::rebirth()` (the stat math + logs, see [rebirth]); then the
 *     command's MONTH log "은퇴하였습니다. <1>date</>". NO increaseInheritancePoint.
 */
class CheEuntwe(private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {

    override val key: String get() = "che_은퇴"
    override val name: String get() = "은퇴"
    override val category: String get() = "개인"

    /** PHP `getPreReqTurn()` = 1 → the command spans 2 turns. */
    val preReqTurn: Int get() = REQ_AGE_PRE_REQ_TURN

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(reqAge(REQ_AGE))

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft

        // if env.isunited == 0 → CheckHall(generalID) BEFORE rebirth — succession subsystem guarded stub.
        // (env.isunited is not on the typed WorldEnv; the daemon path gates CheckHall at the boundary.)
        // CheckHall(...) — STUB (P6 succession subsystem); no state write here.

        val (g, logs) = rebirth(d.general, pipeline)
        d.general = g
        logs.plainLogs.forEach { context.addPlainLog(it) }
        logs.globalActionLogs.forEach { context.addGlobalActionLog(it) }

        // the command's own MONTH retire line, AFTER rebirth's logs (PHP run() order).
        context.addLog("은퇴하였습니다. <1>${context.date}</>")

        // NO increaseInheritancePoint (the plan's "no inheritance bump"). The trailing unique lottery
        // rides the SEPARATE "unique" rng (reason '은퇴') — a downstream seam.
    }

    companion object {
        const val REQ_AGE = 60
        const val REQ_AGE_PRE_REQ_TURN = 1

        /**
         * ReqGeneralValue('age','나이','>=',reqAge,…) reading meta['age']. Keeps the PHP constraint
         * identity name "ReqGeneralValue" and the derived reason "나이가 {reqAge}세 이상이어야 합니다.".
         */
        fun reqAge(reqAge: Int) = object : Constraint {
            override val name = "ReqGeneralValue"
            override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
            override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
                val g = view.get(RequirementKey.General(ctx.actorId)) as? General
                    ?: return ConstraintResult.Unknown(requires(ctx))
                return if (metaInt(g.meta, "age") >= reqAge) ConstraintResult.Allow
                else ConstraintResult.Deny("나이가 ${reqAge}세 이상이어야 합니다.")
            }
        }
    }
}
