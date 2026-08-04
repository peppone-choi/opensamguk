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
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * `GetNationColors()` — faithful port of `legacy/devsam-core/hwe/func_legacy.php:105-114`. The flat
 * color list, indexed by the `colorType` integer arg; `nation.color = GetNationColors()[colorType]`.
 */
internal val NATION_COLORS: List<String> = listOf(
    "#FF0000", "#800000", "#A0522D", "#FF6347", "#FFA500", "#FFDAB9", "#FFD700", "#FFFF00",
    "#7CFC00", "#00FF00", "#808000", "#008000", "#2E8B57", "#008080", "#20B2AA", "#6495ED", "#7FFFD4",
    "#AFEEEE", "#87CEEB", "#00FFFF", "#00BFFF", "#0000FF", "#000080", "#483D8B", "#7B68EE", "#BA55D3",
    "#800080", "#FF00FF", "#FFC0CB", "#F5F5DC", "#E0FFFF", "#FFFFFF", "#A9A9A9",
)

/**
 * che_국기변경 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_국기변경.php`.
 *
 * Changes the nation flag color. Gated by `can_국기변경 > 0`. On success: exp/ded += 5 (preReqTurn=0);
 * `aux[can_국기변경]=0`; `nation.color = GetNationColors()[colorType]`; inline color-styled action +
 * global logs; active_action+1.
 */
fun cheGukgiByeongyeong(pipeline: GeneralActionPipeline): NationCommand = object : NationCommand() {
    override val key: String get() = "che_국기변경"
    override val name: String get() = "국기변경"
    override val argsSchema: Map<String, Any?> get() = mapOf("colorType" to "int")
    override val formSpec: CommandFormSpec get() = CommandFormSpec(
        listOf(CommandFieldSpec("colorType", "int", "select", "nationColors")),
    )

    override fun getPreReqTurn(): Int = 0

    private fun gateConstraints(): List<Constraint> = listOf(
        occupiedCity(), beChief(), suppliedCity(),
        reqNationAuxValue("can_국기변경", 0, ">", 0, "더이상 변경이 불가능합니다."),
    )

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = gateConstraints()
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = gateConstraints()

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf("colorType" to (raw["colorType"] as? Number)?.toInt())

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val nation = d.nation ?: return
        val colorType = (context.args["colorType"] as? Number)?.toInt() ?: return
        val color = NATION_COLORS.getOrNull(colorType) ?: return

        val expRes = addExperience(d.general, expDedMagnitude().toDouble(), pipeline)
        val dedRes = addDedication(expRes.general, expDedMagnitude().toDouble(), pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // nation: color set + aux[can_국기변경]=0 (che_국기변경.php:125-131)
        d.nation = nation.copy(color = color, meta = withNationAux(nation.meta, "can_국기변경" to 0))

        val josaYi = JosaUtil.pick(context.generalName, "이")
        context.addLog("<span style='color:$color;'><b>국기</b></span>를 변경하였습니다 <1>${context.date}</>")
        context.addGlobalActionLog(
            "<Y>${context.generalName}</>$josaYi <span style='color:$color;'><b>국기</b></span>를 변경하였습니다")
    }
}
