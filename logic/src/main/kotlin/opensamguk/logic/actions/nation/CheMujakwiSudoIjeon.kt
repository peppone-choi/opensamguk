package opensamguk.logic.actions.nation

import opensamguk.common.constants.CityConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beLord
import opensamguk.logic.constraints.beOpeningPart
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqNationAuxValue
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_무작위수도이전 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_무작위수도이전.php`.
 *
 * NPC-lord scope-flagged command (rawClassName `che_무작위수도이전`, actionName `무작위 수도 이전`). The ONLY
 * RNG is `rng->choice(candidateCities)` over the neutral level-5/6 cities (engine-scanned, threaded as
 * [GeneralActionResolveContext.candidateCityIds]). On run(): the chosen city → nation; old capital →
 * neutral; nation.capital → chosen; ALL of the nation's generals move to the chosen city (actor +
 * `draft.cascadeGenerals`); aux `can_무작위수도이전 -= 1`; exp/ded += 5*(preReqTurn+1) (preReqTurn=1 → 10).
 * Empty candidate set → push `이동할 수 있는 도시가 없습니다.` and stop.
 */
fun cheMujakwiSudoIjeon(pipeline: GeneralActionPipeline): NationCommand = object : NationCommand() {
    override val key: String get() = "che_무작위수도이전"
    override val name: String get() = "무작위 수도 이전"
    override val rawClassName: String get() = "che_무작위수도이전"  // class ≠ che_+de-spaced-name

    override fun getPreReqTurn(): Int = 1

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val relYear = ((ctx.env["year"] as? Number)?.toInt() ?: 0) - ((ctx.env["startYear"] as? Number)?.toInt() ?: 0)
        return listOf(
            occupiedCity(), beLord(), suppliedCity(),
            beOpeningPart { _, _ -> relYear + 1 },
            reqNationAuxValue("can_무작위수도이전", 0, ">", 0, "더이상 변경이 불가능합니다."),
        )
    }

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val nation = d.nation ?: return
        val oldCapitalId = nation.capitalCityId

        // che_무작위수도이전.php:98-103 — no candidate → stop with the fail log.
        if (context.candidateCityIds.isEmpty()) {
            context.addLog("이동할 수 있는 도시가 없습니다. <1>${context.date}</>")
            return
        }

        val destCityId = context.rng.choice(context.candidateCityIds)   // the ONLY RNG draw
        val destCityName = CityConst.byId(destCityId)?.name ?: ""
        val josaRo = JosaUtil.pick(destCityName, "로")

        // exp/ded += 10
        val expRes = addExperience(d.general, expDedMagnitude().toDouble(), pipeline)
        val dedRes = addDedication(expRes.general, expDedMagnitude().toDouble(), pipeline)
        // actor moves to dest (che_무작위수도이전.php:140)
        d.general = dedRes.general.copy(cityId = destCityId)
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // nation: capital → dest + aux can_무작위수도이전 -= 1 (che_무작위수도이전.php:122-132)
        @Suppress("UNCHECKED_CAST")
        val curAux = (nation.meta["aux"] as? Map<String, Any?>) ?: emptyMap()
        val curCount = (curAux["can_무작위수도이전"] as? Number)?.toInt() ?: 0
        d.nation = nation.copy(
            capitalCityId = destCityId,
            meta = withNationAux(nation.meta, "can_무작위수도이전" to curCount - 1),
        )

        // city ownership flip: dest → nation, old capital → neutral (che_무작위수도이전.php:125-138)
        d.cascadeCities.add(CityConst.byId(destCityId)?.let {
            opensamguk.logic.domain.City(
                id = destCityId, nationId = nation.id, level = 0,
                commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
                supplyState = 1, frontState = 0, trust = 0.0,
            )
        } ?: return)
        if (oldCapitalId != null) {
            d.cascadeCities.add(
                opensamguk.logic.domain.City(
                    id = oldCapitalId, nationId = 0, level = 0,
                    commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
                    supplyState = 1, frontState = 0, trust = 0.0,
                ),
            )
        }

        // all OTHER nation generals move to dest (che_무작위수도이전.php:141-146)
        for (i in d.cascadeGenerals.indices) {
            val g = d.cascadeGenerals[i].copy(cityId = destCityId)
            d.cascadeGenerals[i] = g
            // per-target PLAIN log (che_무작위수도이전.php:147-151)
            context.addPlainLogTo(g.id, "국가 수도를 <G><b>$destCityName</b></>$josaRo 옮겼습니다.")
        }

        // actor logs (che_무작위수도이전.php:156 general action + :159 global action)
        val josaYi = JosaUtil.pick(context.generalName, "이")
        context.addLog("<G><b>$destCityName</b></>$josaRo 국가를 옮겼습니다. <1>${context.date}</>")
        context.addGlobalActionLog(
            "<Y>${context.generalName}</>$josaYi <G><b>$destCityName</b></>$josaRo <M>수도 이전</>하였습니다.")
    }
}
