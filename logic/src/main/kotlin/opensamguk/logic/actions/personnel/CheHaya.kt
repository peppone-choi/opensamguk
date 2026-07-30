package opensamguk.logic.actions.personnel

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.notLord
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.NpcType
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_하야 — faithful port of `che_하야.php` run().
 *
 * The FIRST cross-entity personnel write: betray-scaled exp/ded decay, a gold/rice clamp whose lost
 * delta returns to the (abandoned) nation, the membership reset, the nation gennum decrement (skipped
 * for NPCType 5), and the troop dissolution when the actor is its own troop leader.
 *
 *   - experience *= (1 - 0.1*betray); addExperience(0, affectTrigger=false) recomputes the level only.
 *   - dedication *= (1 - 0.1*betray); addDedication(0, false) recomputes the level only.
 *   - betray + 1, clamped to GameConst.maxBetrayCnt (9).
 *   - newGold = valueFit(gold, max=defaultGold); lost = gold - newGold → nation.gold += lost. (rice same.)
 *   - nation gennum -= (NPCType != 5 ? 1 : 0)  — on the ABANDONED nation.
 *   - nation=0, officer_level=0, officer_city=0, belong=0, makelimit=12.
 *   - troop dissolution: if the actor leads its own troop, every cascade member's troop → 0; self troop → 0.
 *   - increaseInheritancePoint(active_action, 1) — succession seam (OQ7/P6), no write here. NO lottery is
 *     drawn for 하야 (the trailing lottery rides a separate "unique" rng and is a downstream seam).
 */
class CheHaya(private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {

    override val key: String get() = "che_하야"
    override val name: String get() = "하야"
    override val category: String get() = "국가"

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(),
        notLord(),
    )

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val nation = d.nation ?: error("che_하야 requires draft.nation (the abandoned nation)")
        val generalName = (d.general.meta["name"] as? String) ?: ""
        val josaYi = JosaUtil.pick(generalName, "이")
        val nationName = nation.name

        // logs: general action (MONTH) + global action (MONTH global). History buckets are GATE-RUNTIME seams.
        context.addLog("<D><b>$nationName</b></>에서 하야했습니다. <1>${context.date}</>")
        context.addGlobalActionLog("<Y>$generalName</>$josaYi <D><b>$nationName</b></>에서 <R>하야</>했습니다.")

        var g = d.general
        val originalTroopLeaderId = g.troop
        val generalId = g.id
        val betray = metaInt(g.meta, "betray")
        val factor = 1 - 0.1 * betray

        // betray-scaled decay; addExperience/addDedication with 0 delta + affectTrigger=false recomputes level.
        g = g.copy(experience = g.experience * factor)
        val experienceResult = addExperience(g, 0.0, pipeline, affectTrigger = false)
        g = experienceResult.general
        experienceResult.plainLog?.let(context::addPlainLog)
        g = g.copy(dedication = g.dedication * factor)
        val dedicationResult = addDedication(g, 0.0, pipeline, affectTrigger = false)
        g = dedicationResult.general
        dedicationResult.plainLog?.let(context::addPlainLog)

        // betray + 1 cap 9 (increaseVarWithLimit('betray', 1, null, maxBetrayCnt)).
        val nextBetray = minOf(betray + 1, GameConst.maxBetrayCnt)

        // gold/rice clamp; lost delta returns to the nation.
        val newGold = minOf(g.gold, GameConst.defaultGold)
        val newRice = minOf(g.rice, GameConst.defaultRice)
        val lostGold = g.gold - newGold
        val lostRice = g.rice - newRice

        // nation: gold += lostGold, rice += lostRice, gennum -= (npc != 5 ? 1 : 0).
        val gennumDrop = if (NpcType.decrementsGennumOnRetire(g)) 1 else 0
        d.nation = nation.copy(
            gold = nation.gold + lostGold,
            rice = nation.rice + lostRice,
            gennum = nation.gennum - gennumDrop,
            meta = withMeta(nation.meta, "gennum" to nation.gennum - gennumDrop),
        )

        // membership reset.
        g = g.copy(
            gold = newGold,
            rice = newRice,
            nationId = 0,
            officerLevel = 0,
            troop = 0,
            meta = withMeta(g.meta, "officer_city" to 0, "belong" to 0, "makelimit" to GameConst.maxChiefTurn, "betray" to nextBetray),
        )

        // troop dissolution: when the actor is its own troop leader, every member's troop → 0.
        if (originalTroopLeaderId == generalId) {
            for (i in d.cascadeGenerals.indices) {
                if (d.cascadeGenerals[i].troop == generalId) {
                    d.cascadeGenerals[i] = d.cascadeGenerals[i].copy(troop = 0)
                }
            }
        }

        // increaseInheritancePoint(active_action, 1) — succession seam; no write here.
        val statChange = checkStatChange(g.copy(lastTurn = LastTurn(command = name, arg = null)))
        d.general = statChange.general
        statChange.plainLogs.forEach(context::addPlainLog)
        StaticEventHandler.handleEvent(d.general, d.destGeneral, key, emptyMap(), context.args)
    }
}
