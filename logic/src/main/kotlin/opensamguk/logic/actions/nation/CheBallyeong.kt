package opensamguk.logic.actions.nation

import opensamguk.common.constants.CityConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.existsDestGeneral
import opensamguk.logic.constraints.friendlyDestGeneral
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.occupiedDestCity
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.constraints.suppliedDestCity
import opensamguk.logic.domain.withMeta
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_발령 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_발령.php`.
 *
 * Reassigns a FRIENDLY dest general to a friendly occupied/supplied dest city. The actor's command
 * (chief only). Mutates the DEST general (`setVar('city', destCityID)` + `last발령` aux jsonb), writes
 * a line to BOTH the dest general's log scope AND the actor's, and grants ZERO exp/ded (NO inheritance).
 *
 * self-target → AlwaysFail '본인입니다' (initWithArg). getFailString appends the dest general name.
 *
 * `last발령` = joinYearMonth(year, month), +1 when the dest general's turn falls in a DIFFERENT turn
 * bucket than the actor's (che_발령.php:161-165). The turn-bucket comparison is engine wall-clock math
 * (turnTime not on the logic General) so the +1 decision is supplied via
 * [GeneralActionResolveContext.destDifferentTurnBucket].
 *
 * Apply ORDER (che_발령.php:168-171): setResultTurn → applyDB(general) → applyDB(destGeneral) → handleEvent.
 */
fun cheBallyeong(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): NationCommand = object : NationCommand() {
    override val key: String get() = "che_발령"
    override val name: String get() = "발령"
    override val reservable: Boolean get() = true
    override val argsSchema: Map<String, Any?> get() = mapOf("destGeneralID" to "int", "destCityID" to "int")

    override fun getPreReqTurn(): Int = 0

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(), notBeNeutral(), occupiedCity(), suppliedCity(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        // initWithArg: self-target → AlwaysFail '본인입니다'.
        if ((ctx.args["destGeneralID"] as? Number)?.toInt() == ctx.actorId) {
            return listOf(alwaysFail("본인입니다"))
        }
        return listOf(
            beChief(), notBeNeutral(), occupiedCity(), suppliedCity(),
            existsDestGeneral(), friendlyDestGeneral(), occupiedDestCity(), suppliedDestCity(),
        )
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "destGeneralID" to (raw["destGeneralID"] as? Number)?.toInt(),
        "destCityID" to (raw["destCityID"] as? Number)?.toInt(),
    )

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val destGeneral = d.destGeneral ?: return
        val destCityId = (context.args["destCityID"] as? Number)?.toInt() ?: return
        val destCityName = CityConst.byId(destCityId)?.name ?: ""

        // dest general → destCityID (che_발령.php:155)
        var movedDest = destGeneral.copy(cityId = destCityId)

        // dest-scope log (raw body; the dest logger applies its OWN month prefix at flush — :159)
        val josaRoDest = JosaUtil.pick(destCityName, "로")
        context.addLogTo(
            destGeneral.id,
            "<Y>${context.generalName}</>에 의해 <G><b>$destCityName</b></>$josaRoDest 발령됐습니다. <1>${context.date}</>")

        // last발령 = joinYearMonth +1-if-different-turn-bucket, on the dest general's aux jsonb (:161-165)
        var lastBallyeong = joinYearMonth(context.env.year, context.month)
        if (context.destDifferentTurnBucket) lastBallyeong += 1
        movedDest = movedDest.copy(meta = withAux(movedDest.meta, "last발령" to lastBallyeong))
        d.destGeneral = movedDest

        // actor-scope log (che_발령.php:166)
        val josaUl = JosaUtil.pick(context.destGeneralName, "을")
        context.addLog(
            "<Y>${context.destGeneralName}</>$josaUl <G><b>$destCityName</b></>$josaRoDest 발령했습니다. <1>${context.date}</>")

        // ZERO exp/ded; NO inheritance.
    }
}

/**
 * Write `key=value` into the nested `aux` map on a general's `meta` jsonb (delete-on-null at flush),
 * preserving the existing aux entries + insertion order. Mirrors PHP `General::setAuxVar`.
 */
internal fun withAux(meta: Map<String, Any?>, vararg pairs: Pair<String, Any?>): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val curAux = (meta["aux"] as? Map<String, Any?>) ?: emptyMap()
    val nextAux = LinkedHashMap(curAux)
    for ((k, v) in pairs) nextAux[k] = v
    return withMeta(meta, "aux" to nextAux)
}
