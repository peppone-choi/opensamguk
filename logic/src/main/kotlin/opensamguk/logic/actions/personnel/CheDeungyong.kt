package opensamguk.logic.actions.personnel

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView
import opensamguk.logic.constraints.differentNationDestGeneral
import opensamguk.logic.constraints.existsDestGeneral
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqGeneralGold
import opensamguk.logic.constraints.reqGeneralRice
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.domain.General
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound

/**
 * The MINIMAL scout-letter row che_등용 INSERTs — the letter payload only (no mailbox lifecycle). The full
 * Message/MessageTarget/agreeMessage/buildFromArray mailbox subsystem (+ 등용수락/decline) is DEFERRED to P6.
 */
data class ScoutLetterInsert(
    val srcGeneralId: Int,
    val destGeneralId: Int,
    val type: String,
    val text: String,
)

/**
 * che_등용 — SEND ONLY (faithful port of `che_등용.php` run(); 등용수락/decline/mailbox DEFERRED to P6,
 * research OQ7 RESOLVED).
 *
 *   - getCost(): `Util::round(env.develcost + (dest.experience + dest.dedication)/1000) * 10` — the round
 *     is applied BEFORE the `*10` (che_등용.php:111-114). Rice cost is 0.
 *   - run(): push the send log "<Y>{destName}</>에게 등용 권유 서신을 보냈습니다. <1>{date}</>"; then the
 *     self-mutation addExperience(100)/addDedication(200)/increaseVar('leadership_exp',1)/
 *     increaseVarWithLimit('gold',-reqGold,0); checkStatChange; and a MINIMAL `message` row INSERT
 *     ([ScoutLetterInsert], the letter payload only). It does NOT transition anyone and does NOT call
 *     increaseInheritancePoint. The trailing unique lottery rides the SEPARATE "unique" rng (downstream seam).
 *
 * The built scout letter is exposed via [lastScoutLetter] (captured on resolve) — the satellite/KV write
 * the GATE-SATELLITE/flush layer picks up; modeling it on the shared draft is a P6 mailbox concern.
 */
class CheDeungyong(private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {

    override val key: String get() = "che_등용"
    override val name: String get() = "등용"
    override val category: String get() = "인사"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destGeneralID" to "int")

    /** The scout letter built by the last [resolve] (the minimal message-row INSERT). */
    var lastScoutLetter: ScoutLetterInsert? = null
        private set

    /** che_등용.php:104-116 getCost — round BEFORE *10; rice cost 0. */
    fun getCost(destGeneral: General, env: WorldEnv): Int =
        phpRound(env.develCost + (destGeneral.experience + destGeneral.dedication) / 1000) * 10

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(),
        occupiedCity(),
        suppliedCity(),
        existsDestGeneral(),
        differentNationDestGeneral(),
        reqGeneralGold { c, view ->
            val dest = c.destGeneralId?.let { view.get(RequirementKey.DestGeneral(it)) as? General }
            if (dest == null) Int.MAX_VALUE else getCost(dest, envOf(c))
        },
        reqGeneralRice { _, _ -> 0 },
    )

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val dest = d.destGeneral ?: error("che_등용 requires a destGeneral")
        val destName = (dest.meta["name"] as? String) ?: ""

        // send log (MONTH).
        context.addLog("<Y>$destName</>에게 등용 권유 서신을 보냈습니다. <1>${context.date}</>")

        val reqGold = getCost(dest, context.env)

        // self-mutation (PHP order): +exp100 → +ded200 → +leadership_exp1 → gold -= reqGold (floor 0).
        var g = d.general
        val expRes = addExperience(g, 100.0, pipeline); g = expRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        val dedRes = addDedication(g, 200.0, pipeline); g = dedRes.general
        dedRes.plainLog?.let { context.addPlainLog(it) }
        g = g.copy(meta = withMeta(g.meta, "leadership_exp" to metaDouble(g.meta, "leadership_exp") + 1))
        g = g.copy(gold = maxOf(0, g.gold - reqGold))

        // checkStatChange (PHP run() finalize) — routes any PLAIN stat-change logs.
        val statRes = checkStatChange(g); g = statRes.general
        statRes.plainLogs.forEach { context.addPlainLog(it) }
        d.general = g

        // MINIMAL message-row INSERT (letter payload only). srcNationName from the actor's nation.
        val srcNationName = d.nation?.name ?: ""
        val josaRo = JosaUtil.pick(srcNationName, "로")
        lastScoutLetter = ScoutLetterInsert(
            srcGeneralId = d.general.id,
            destGeneralId = dest.id,
            type = "scout",
            text = "$srcNationName$josaRo 망명 권유 서신",
        )

        // NO transition, NO increaseInheritancePoint (the SEND-only scope).
    }

    private fun envOf(c: ConstraintContext) = WorldEnv(
        year = (c.env["year"] as Number).toInt(),
        startYear = (c.env["startYear"] as Number).toInt(),
        develCost = (c.env["develCost"] as Number).toInt(),
    )
}
