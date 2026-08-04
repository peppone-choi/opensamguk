package opensamguk.logic.actions.nation

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.CommandFieldSpec
import opensamguk.logic.actions.CommandFormSpec
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqNationAuxValue
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_국호변경 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_국호변경.php`.
 *
 * Renames the nation. Gated by the nation aux `can_국호변경 > 0` (ReqNationAuxValue). On run():
 *  - a RUNTIME dup-name re-check (che_국호변경.php:128) — if another nation already holds the name, push
 *    the EXACT fail log `이미 같은 국호를 가진 곳이 있습니다. 국호변경 실패 <1>{date}</>` and stop WITHOUT
 *    consuming the aux (the dup result is threaded via [GeneralActionResolveContext.runtimeNameDuplicate]).
 *  - on success: exp/ded += 5 (preReqTurn=0 → 5; NOT +30, TS diverges); `aux[can_국호변경]=0`; nation.name
 *    set; the inline-style general action + global action logs; active_action+1 (inheritance, P6 seam).
 */
fun cheGukhoByeongyeong(pipeline: GeneralActionPipeline): NationCommand = object : NationCommand() {
    override val key: String get() = "che_국호변경"
    override val name: String get() = "국호변경"
    override val argsSchema: Map<String, Any?> get() = mapOf("nationName" to "string")
    override val formSpec: CommandFormSpec get() = CommandFormSpec(
        listOf(CommandFieldSpec("nationName", "string", "text", required = true, min = 1, max = 18)),
    )

    override fun getPreReqTurn(): Int = 0

    private fun gateConstraints(): List<Constraint> = listOf(
        occupiedCity(), beChief(), suppliedCity(),
        reqNationAuxValue("can_국호변경", 0, ">", 0, "더이상 변경이 불가능합니다."),
    )

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = gateConstraints()
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = gateConstraints()

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf("nationName" to raw["nationName"] as? String)

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val nation = d.nation ?: return
        val newName = context.args["nationName"] as? String ?: return

        // RUNTIME dup-name fail (che_국호변경.php:128-132) — exact log, stop, do NOT consume aux.
        if (context.runtimeNameDuplicate) {
            context.addLog("이미 같은 국호를 가진 곳이 있습니다. $name 실패 <1>${context.date}</>")
            return
        }

        // exp/ded += 5
        val expRes = addExperience(d.general, expDedMagnitude().toDouble(), pipeline)
        val dedRes = addDedication(expRes.general, expDedMagnitude().toDouble(), pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // nation: name set + aux[can_국호변경]=0 (che_국호변경.php:143-149)
        d.nation = nation.copy(name = newName, meta = withNationAux(nation.meta, "can_국호변경" to 0))

        val josaRo = JosaUtil.pick(newName, "로")
        val josaYi = JosaUtil.pick(context.generalName, "이")
        context.addLog("국호를 <D><b>$newName</b></>$josaRo 변경합니다. <1>${context.date}</>")
        context.addGlobalActionLog("<Y>${context.generalName}</>$josaYi 국호를 <D><b>$newName</b></>$josaRo 변경합니다.")
    }
}

/**
 * Write `key=value` into the nested `aux` map on a NATION's `meta` jsonb (rate/bill/aux ride meta),
 * preserving existing entries + insertion order. Mirrors PHP `nation['aux'][key] = value` + Json::encode.
 */
/** Merge keys into nation.meta['aux'] LinkedHashMap preserving insertion order (research unlocks). */
fun withNationAux(meta: Map<String, Any?>, vararg pairs: Pair<String, Any?>): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val curAux = (meta["aux"] as? Map<String, Any?>) ?: emptyMap()
    val nextAux = LinkedHashMap(curAux)
    for ((k, v) in pairs) nextAux[k] = v
    return withMeta(meta, "aux" to nextAux)
}
