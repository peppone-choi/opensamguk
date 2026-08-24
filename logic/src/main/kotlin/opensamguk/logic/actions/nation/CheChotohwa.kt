package opensamguk.logic.actions.nation

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.activeMapDestCity
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.occupiedDestCity
import opensamguk.logic.constraints.reqNationValue
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.constraints.suppliedDestCity
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CityConstRegistry

/**
 * che_초토화 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_초토화.php`.
 *
 * special 사령 커맨드. 아국 비수도 보급 dest 도시를 공백지(중립, scorched)로 만든다. 평시(surlimit==0
 * + DisallowDiplomacyStatus([0]) = state != 0)에만 가능. pushGlobalActionLog broadcast(=true).
 * `addExperience(-exp*0.1)`로 액터 exp를 의도적으로 낮춘다(레벨 다운 가능 → levelCross=true).
 * RNG 미사용(deterministic). logLines=1, broadcast.
 *
 * argTest(che_초토화.php:31-46): `destCityID`(CityConst::byID 존재) → canonical `{destCityID}`.
 *
 * fullConditionConstraints(che_초토화.php:65-82), PHP ORDER:
 *   [OccupiedCity, OccupiedDestCity, BeChief, SuppliedCity, SuppliedDestCity,
 *    ReqNationValue('capital','수도','!=',destCity,'수도입니다.'),
 *    ReqNationValue('surlimit','제한 턴','==',0,'외교제한 턴이 남아있습니다.'),
 *    DisallowDiplomacyStatus(nationID, {0:'평시에만 가능합니다.'})].
 *
 * Live path: ProcessNationCommand CommandRegistry bridge drains destCity/nation/general.
 *   addExperience(-exp*0.1), pushGlobalActionLog broadcast + 초토화 발동/전역 로그.
 */
fun cheChotohwa(pipeline: GeneralActionPipeline): CheChotohwa =
    CheChotohwa(pipeline)

class CheChotohwa(private val pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_초토화"
    override val name: String get() = "초토화"
    override val category: String get() = "전략"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destCityID" to "int")

    /** che_초토화.php:getPreReqTurn = 2 (reqTurn = 3). */
    override fun getPreReqTurn(): Int = 2

    /** Structural parser; active-map membership is enforced by the first FULL constraint. */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        val destCityID = (raw["destCityID"] as? Number)?.toInt() ?: return null
        if (destCityID <= 0) return null
        return linkedMapOf("destCityID" to destCityID)
    }

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        occupiedCity(), beChief(), suppliedCity(),
        reqNationValue("surlimit", "제한 턴", "==", 0, "외교제한 턴이 남아있습니다."),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        activeMapDestCity(), occupiedCity(), occupiedDestCity(), beChief(), suppliedCity(), suppliedDestCity(),
        notCapitalDestCity(),  // ReqNationValue('capital','수도','!=',destCity,'수도입니다.')
        reqNationValue("surlimit", "제한 턴", "==", 0, "외교제한 턴이 남아있습니다."),
        disallowDiplomacyStatus(linkedMapOf(0 to "평시에만 가능합니다.")),
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    /** che_초토화.php:115-122 calcReturnAmount — pop/5 * Π((res - res_max*0.5)/res_max + 0.8), Util::toInt(truncate). */
    fun calcReturnAmount(city: opensamguk.logic.domain.City): Int {
        var amount = city.population.toDouble() / 5.0
        val resPairs = listOf(
            city.agriculture to city.agricultureMax,
            city.commerce to city.commerceMax,
            city.security to city.securityMax,
        )
        for ((res, resMax) in resPairs) {
            amount *= ((res - resMax * 0.5) / resMax) + 0.8
        }
        return amount.toInt()  // Util::toInt = truncate toward zero
    }

    /**
     * che_초토화.php:130-211 run() 충실 포팅.
     *
     * 흐름:
     *  1. actor exp -= exp*0.1 (affectTrigger=false, 레벨만 재계산), exp/ded += 5*(preReqTurn+1) (:154-156).
     *  2. dest level>=8 이면 nation aux[did_특성초토화]+1 (:164-166) — 골든 city level=6이라 미발동.
     *  3. 타국 officer_level>=5 장수 exp*=0.9 (UPDATE, candidate-set) + nation 전체 betray+1 (:168-174).
     *  4. actor betray+1 (increaseVar, uncapped) (:175).
     *  5. dest 도시 공백지화: trust>=50, pop/agri/comm/secu/def *0.2 vs *_max*0.1 중 큰 값, wall *0.5 vs
     *     *_max*0.1, nation=0, front=0, conflict={} (:177-188).
     *  6. nation gold/rice += amount, surlimit += postReqTurn(24), aux 갱신 (:190-195).
     *  7. active_action+1 (:200) + 5종 로그 (:201-205). actor action + global broadcast가 골든 타겟.
     *
     * RNG 미사용 — deterministic(draw_count=0). 추첨 추가 금지.
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val nation = d.nation ?: return
        val destCityId = (context.args["destCityID"] as? Number)?.toInt() ?: return
        val destCityName = CityConstRegistry.of(context.env.mapName).byId(destCityId)?.name ?: ""

        // --- actor exp/ded (che_초토화.php:154-156) ---
        // addExperience(-exp*0.1, false): 트리거 미적용(증감폭 그대로), 레벨만 재계산.
        var g = d.general
        val expDed = 5 * (getPreReqTurn() + 1)  // 5*(2+1) = 15
        g = addExperience(g, -g.experience * 0.1, pipeline, affectTrigger = false).general
        val expRes = addExperience(g, expDed.toDouble(), pipeline)
        val dedRes = addDedication(expRes.general, expDed.toDouble(), pipeline)
        g = dedRes.general
        // 레벨업/다운 로그는 PLAIN 라인(있을 때만). 골든 케이스(exp 40→36→51)는 레벨 불변.
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // --- actor betray+1 (increaseVar, uncapped — che_초토화.php:175) ---
        g = g.copy(meta = withMeta(g.meta, "betray" to metaInt(g.meta, "betray") + 1))
        d.general = g

        // --- 타국 betray+1 / officer_level>=5 exp*0.9 (candidate-set, che_초토화.php:168-174) ---
        // UPDATE general SET betray=betray+1 WHERE nation=me AND no!=actor;
        // UPDATE general SET experience*=0.9 WHERE nation=me AND officer_level>=5 AND no!=actor.
        for (cg in context.candidateGenerals) {
            if (cg.id == g.id) continue
            var moved = cg.copy(meta = withMeta(cg.meta, "betray" to metaInt(cg.meta, "betray") + 1))
            if (cg.officerLevel >= 5) {
                moved = addExperience(moved, -moved.experience * 0.1, pipeline, affectTrigger = false).general
            }
            d.cascadeGenerals.add(moved)
        }

        // --- amount/destLevel는 공백지화 BEFORE의 원본 dest로 계산 (che_초토화.php:158 — 도시 UPDATE 이전) ---
        // calcReturnAmount는 원본(미감쇄) pop/agri/comm/secu를 읽는다. 감쇄 후 계산하면 amount가
        // 잘못 작아진다(실행순서 패러티: 로그/부수효과 순서 = 실행 순서).
        val originalDest = d.destCity
        val amount = originalDest?.let { calcReturnAmount(it) }
            ?: context.args["__returnAmount"]?.let { (it as? Number)?.toInt() } ?: 0
        // dest level>=8 → aux[did_특성초토화]+1 (che_초토화.php:164-166). dest 미제공 시 level은 args로.
        val destLevel = (context.args["__destLevel"] as? Number)?.toInt() ?: originalDest?.level

        // --- dest 도시 공백지화 (che_초토화.php:177-188) ---
        // greatest(*_max*0.1, *) — pop/agri/comm/secu/def *0.2, wall *0.5; truncate(toInt).
        originalDest?.let { dc ->
            d.destCity = dc.copy(
                nationId = 0,
                frontState = 0,
                conflict = "{}",
                trust = maxOf(50.0, dc.trust),
                population = maxOf(dc.populationMax * 0.1, dc.population * 0.2).toInt(),
                agriculture = maxOf(dc.agricultureMax * 0.1, dc.agriculture * 0.2).toInt(),
                commerce = maxOf(dc.commerceMax * 0.1, dc.commerce * 0.2).toInt(),
                security = maxOf(dc.securityMax * 0.1, dc.security * 0.2).toInt(),
                defense = maxOf(dc.defenseMax * 0.1, dc.defense * 0.2).toInt(),
                wall = maxOf(dc.wallMax * 0.1, dc.wall * 0.5).toInt(),
            )
        }

        // --- nation 국고/surlimit/aux (che_초토화.php:190-195) ---
        // amount(원본 기준) gold/rice += amount; surlimit += postReqTurn(24).
        var nationMeta = nation.meta
        if (destLevel != null && destLevel >= 8) {
            @Suppress("UNCHECKED_CAST")
            val aux = (nationMeta["aux"] as? Map<String, Any?>) ?: emptyMap()
            val cur = (aux["did_특성초토화"] as? Number)?.toInt() ?: 0
            nationMeta = withNationAux(nationMeta, "did_특성초토화" to cur + 1)
        }
        val surlimit = metaInt(nation.meta, "surlimit") + 24  // getPostReqTurn()
        d.nation = nation.copy(
            gold = nation.gold + amount,
            rice = nation.rice + amount,
            meta = withMeta(nationMeta, "surlimit" to surlimit),
        )

        // --- 로그 (che_초토화.php:201-205) ---
        val josaUl = JosaUtil.pick(destCityName, "을")
        val josaYi = JosaUtil.pick(context.generalName, "이")
        context.addLog("<G><b>$destCityName</b></>$josaUl 초토화했습니다. <1>${context.date}</>")
        context.addGlobalActionLog(
            "<Y>${context.generalName}</>$josaYi <G><b>$destCityName</b></>$josaUl <M>초토화</>하였습니다.")
    }
}

/**
 * `ReqNationValue('capital','수도','!=',destCity,'수도입니다.')` — dest 도시가 아국 수도가 아니어야 함.
 * nation `capital`은 `capitalCityId`(nationNumField 비교표 밖). nation 패밀리 로컬(che_천도 notAlreadyCapital
 * 패턴 — 단 메시지는 '수도입니다.'). `ctx.args['destCityID']`를 읽는다.
 */
private fun notCapitalDestCity(): Constraint = object : Constraint {
    override val name = "ReqNationValue"
    override fun requires(ctx: ConstraintContext) =
        listOf(opensamguk.logic.constraints.RequirementKey.Nation(ctx.nationId ?: 0))
    override fun test(ctx: ConstraintContext, view: opensamguk.logic.constraints.StateView):
        opensamguk.logic.constraints.ConstraintResult {
        val n = view.get(opensamguk.logic.constraints.RequirementKey.Nation(ctx.nationId ?: 0))
            as? opensamguk.logic.domain.Nation
            ?: return opensamguk.logic.constraints.ConstraintResult.Unknown(requires(ctx))
        val destCityId = (ctx.args["destCityID"] as? Number)?.toInt()
        return if (n.capitalCityId != destCityId) opensamguk.logic.constraints.ConstraintResult.Allow
        else opensamguk.logic.constraints.ConstraintResult.Deny("수도입니다.")
    }
}
