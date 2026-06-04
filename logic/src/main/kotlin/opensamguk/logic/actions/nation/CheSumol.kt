package opensamguk.logic.actions.nation

import opensamguk.common.constants.CityConst
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.battleGroundCity
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.notNeutralDestCity
import opensamguk.logic.constraints.notOccupiedDestCity
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound

/**
 * che_수몰 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_수몰.php`.
 *
 * 전략(strategic) 사령 커맨드. 교전중(state 0) 적 BattleGroundCity의 수비(def)/성벽(wall)을 *0.2 한다.
 * dest-nation PLAIN + national-history 라인. RNG 미사용(deterministic). logLines=1.
 *
 * argTest(che_수몰.php:31-48): `destCityID`(CityConst::byID 존재) → canonical `{destCityID}`.
 *
 * fullConditionConstraints(che_수몰.php:74-81), PHP ORDER:
 *   [OccupiedCity, BeChief, NotNeutralDestCity, NotOccupiedDestCity, BattleGroundCity, AvailableStrategicCommand].
 *
 * BattleGroundCity의 atWarWithDest 술어는 F-BFS/외교 preload(staging) 대상 — staging seam 미구현 시
 * `ctx.env["__atWarWithDest"]`가 없으면 false(deferring)로 떨어진다(che_선전포고 nearNation 패턴과 동일).
 *
 * run()(che_수몰.php:121-200): RNG 미사용(deterministic, draw_count=0).
 *   1. 액터 action 로그 `수몰 발동! <1>{date}</>`.
 *   2. exp/ded += `5 * (getPreReqTurn() + 1)` = 15 (addExperience/addDedication, onCalcStat fold).
 *   3. 아국 OTHER 장수 로거에 `broadcastMessage`(PLAIN) — 액터 action 로그/글로벌 broadcast 아님 → 게이트 미포함.
 *   4. dest 국가 장수 로거에 `destBroadcastMessage`(PLAIN) + 첫 장수에 national-history — 동일하게 별도 스코프.
 *   5. dest 도시 def/wall *= 0.2 (`def * 0.2` / `wall * 0.2` SQL → phpRound half-away → Int).
 *   6. 액터 general/national history 로그(별도 history 스코프, action 로그 아님).
 *   7. nation.strategic_cmd_limit = onCalcrategic(name,'globalDelay',9).
 */
fun cheSumol(pipeline: GeneralActionPipeline): CheSumol =
    CheSumol(pipeline)

class CheSumol(private val pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_수몰"
    override val name: String get() = "수몰"
    override val category: String get() = "전략"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destCityID" to "int")

    override fun getPreReqTurn(): Int = 2

    /** che_수몰.php:31-48 argTest. CityConst::byID 존재 검증 후 canonical `{destCityID}` 또는 null. */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        val destCityID = (raw["destCityID"] as? Number)?.toInt() ?: return null
        if (CityConst.byId(destCityID) == null) return null
        return linkedMapOf("destCityID" to destCityID)
    }

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        occupiedCity(), beChief(), availableStrategicCommand(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        occupiedCity(), beChief(), notNeutralDestCity(), notOccupiedDestCity(),
        battleGroundCity { c, _ -> (c.env["__atWarWithDest"] as? Boolean) ?: (c.args["__atWarWithDest"] as? Boolean) ?: false },
        availableStrategicCommand(),
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf("destCityID" to (raw["destCityID"] as? Number)?.toInt())

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val nation = d.nation ?: return

        // (1) 액터 action 로그 — che_수몰.php:160 pushGeneralActionLog("수몰 발동! <1>$date</>").
        context.addLog("수몰 발동! <1>${context.date}</>")

        // (2) exp/ded += 5 * (preReqTurn + 1) = 15 (che_수몰.php:162-163). onCalcStat fold(트리거 없으면 항등).
        val expDed = expDedMagnitude().toDouble()
        val expRes = addExperience(d.general, expDed, pipeline)
        val dedRes = addDedication(expRes.general, expDed, pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // (3)(4) 아국 OTHER 장수 + dest 국가 장수 로거(PLAIN) + national-history는 각자의 ActionLogger 스코프로
        //   flush된다(che_수몰.php:171-198) — 액터 action 로그도 글로벌 broadcast도 아니므로 게이트 logLines/
        //   broadcastLines에 포함되지 않는다(골든 broadcastLines=[]). 엔진 cascade 시점에 재현되는 별도 스코프.

        // (5) dest 도시 def/wall *= 0.2 (che_수몰.php:200-203 `def * 0.2` / `wall * 0.2`).
        //   MySQL float→int 컬럼 저장 = 반올림(half-away) → phpRound. (게이트의 actor city 17 != dest city이므로
        //   골든 city after는 불변; 충실한 dest 변이는 draft.destCity로 기록.)
        d.destCity?.let { dc ->
            d.destCity = dc.copy(
                defense = phpRound(dc.defense * 0.2),
                wall = phpRound(dc.wall * 0.2),
            )
        }

        // (7) nation.strategic_cmd_limit = onCalcStrategic(name, 'globalDelay', 9) (che_수몰.php:210-213).
        val nextLimit = phpRound(pipeline.onCalcStrategic(d.general, actionName, "globalDelay", 9.0))
        d.nation = nation.copy(meta = withMeta(nation.meta, "strategic_cmd_limit" to nextLimit))
    }
}
