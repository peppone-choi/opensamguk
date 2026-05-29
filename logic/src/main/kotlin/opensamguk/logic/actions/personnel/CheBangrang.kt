package opensamguk.logic.actions.personnel

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.founding.FoundingCascade
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowDiplomacyStatus
import opensamguk.logic.constraints.beLord
import opensamguk.logic.constraints.notOpeningPart
import opensamguk.logic.constraints.notWanderingNation
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_방랑 — faithful port of `che_방랑.php` run().
 *
 * Lord-only: abandons the nation's territory, reverting the whole nation to a wandering band. The bulk
 * multi-entity cascade ORDER is load-bearing and delegated to [FoundingCascade.applyWanderingCascade]
 * (nation revert → lord self → member demote → city neutralize → diplomacy reset). 방랑 has NO lottery.
 *
 * `increaseInheritancePoint(active_action, 1)` is the succession seam (OQ7/P6) — no write here.
 * `DeleteConflict(nationID)` + the global-history log lines are GATE-RUNTIME seams (no history bucket in
 * the resolve context); the per-city `conflict={}` reset inside the cascade covers the city-level state.
 *
 * The AllowDiplomacyStatus(me=nationID, state IN [2,7], '방랑할 수 없는 외교상태입니다.') existence scan
 * cannot be answered by the per-pair Diplomacy key alone, so the adapter preloads its result as the
 * boolean ctx.args['wanderableDiplomacyExists'] (the same preloaded-predicate idiom as battleGroundCity).
 */
class CheBangrang(private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {

    override val key: String get() = "che_방랑"
    override val name: String get() = "방랑"
    override val category: String get() = "국가"

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beLord(),
        notWanderingNation(),
        notOpeningPart { c, _ -> (c.args["relYear"] as? Number)?.toInt() ?: 0 },
        allowDiplomacyStatus("방랑할 수 없는 외교상태입니다.") { c, _ ->
            (c.args["wanderableDiplomacyExists"] as? Boolean) ?: false
        },
    )

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val generalName = (d.general.meta["name"] as? String) ?: ""
        val josaYi = JosaUtil.pick(generalName, "이")

        // logs: general action (MONTH) + global action (MONTH global). Global/general history are GATE seams.
        context.addLog("영토를 버리고 방랑의 길을 떠납니다. <1>${context.date}</>")
        context.addGlobalActionLog("<Y>$generalName</>$josaYi 방랑의 길을 떠납니다.")

        // the load-bearing bulk cascade (nation→lord→members→cities→diplomacy), in PHP order.
        FoundingCascade.applyWanderingCascade(d, generalName)

        // increaseInheritancePoint(active_action, 1) — succession seam; no write here. NO lottery.
    }
}
