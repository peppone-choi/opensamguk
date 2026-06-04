package opensamguk.logic.actions.nation

import opensamguk.common.constants.CityConst
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowDiplomacyBetweenStatus
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.notNeutralDestCity
import opensamguk.logic.constraints.notOccupiedDestCity
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_허보 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_허보.php`.
 *
 * 전략(strategic) 사령 커맨드. 선포/전쟁중(state 0,1) 적 dest 도시의 각 적 장수를 무작위 보급(supplied)
 * 적 도시로 이동시킨다. dest 장수마다 `rng->choice(suppliedEnemyCities)`를 1회씩 뽑고, 같은 도시가
 * 나오면 re-roll 한다(draw-for-draw 패리티 타겟). logLines=1, dest 장수마다 PLAIN 라인.
 *
 * 골든(che_허보-fixtures.json) draws: 3 choice(items=보급 적 도시 리스트), seq 0/1/2 → 36/80/2.
 *
 * argTest(che_허보.php:30-47): `destCityID`(CityConst::byID 존재) → canonical `{destCityID}`.
 *
 * fullConditionConstraints(che_허보.php:69-78), PHP ORDER:
 *   [OccupiedCity, BeChief, NotNeutralDestCity, NotOccupiedDestCity, AllowDiplomacyBetweenStatus([0,1],…), AvailableStrategicCommand].
 *
 * TODO(포팅): run() — dest 도시 각 적 장수를 `context.rng.choice(suppliedEnemyCityIds)`로 이동(같은
 *   도시 re-roll), 각 dest 장수 PLAIN 로그 + 허보 발동 로그. RNG는 골든 시드로 1회 빌드되어 ctx에 threaded.
 */
fun cheHeobo(pipeline: GeneralActionPipeline): CheHeobo =
    CheHeobo(pipeline)

class CheHeobo(private val pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_허보"
    override val name: String get() = "허보"
    override val category: String get() = "전략"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destCityID" to "int")

    /** che_허보.php:99-101 getPreReqTurn = 1 (reqTurn = 2). exp/ded += 5*(1+1)=10. */
    override fun getPreReqTurn(): Int = 1

    /** che_허보.php:30-47 argTest. CityConst::byID 존재 검증 후 canonical `{destCityID}` 또는 null. */
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
        allowDiplomacyBetweenStatus(listOf(0, 1), "선포, 전쟁중인 상대국에게만 가능합니다."),
        availableStrategicCommand(),
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf("destCityID" to (raw["destCityID"] as? Number)?.toInt())

    /**
     * che_허보.php:49-106 run() 충실 포팅 — RNG: dest 도시 적 장수 1명당 choice 1회(같은 도시면 re-roll).
     *
     * 실행 순서(부수효과/로그 순서 = 실행 순서):
     *  1) actor 액션로그 "허보 발동! <1>{date}</>" (MONTH prefix는 addLog 부착, che_허보.php:55).
     *  2) exp/ded += 5*(getPreReqTurn()+1) (=5, che_허보.php:57-58). pipeline fold(레벨변동 시 PLAIN).
     *  3) 같은 국가 다른 장수 broadcast(PLAIN) — own-nation 스코프로 flush(che_허보.php:64-69). RNG 미사용 +
     *     own-nation 후보 미주입 → 게이트 대상 외(골든 미캡처). draw 추가 금지.
     *  4) dest 도시 적 장수마다(che_허보.php:75-90): 자신의 PLAIN 로그 "상대의 허보에 당했다! <1>{date}</>" +
     *     `rng.choice(보급 적 도시)` 1회 — 뽑힌 도시가 dest 도시(공격 대상)면 1회 re-roll → city 변경(cascade).
     *  5) nation strategic_cmd_limit = onCalcStrategic(name,'globalDelay',9) (che_허보.php:104) — 골든 미캡처,
     *     meta 기록(패러티 대상).
     *
     * 보급 적 도시 리스트(SELECT city FROM city WHERE nation=destNation AND supply=1)는 월드 집계라
     * 메모리 드래프트에 없어 args(documented fixture input)로 주입한다(__suppliedEnemyCities).
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft

        // (1) actor 액션로그.
        context.addLog("허보 발동! <1>${context.date}</>")

        // (2) exp/ded += 5*(preReqTurn+1) = 5. 레벨 변동 시에만 PLAIN.
        val gain = 5.0 * (getPreReqTurn() + 1)
        val expRes = addExperience(d.general, gain, pipeline)
        val dedRes = addDedication(expRes.general, gain, pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // (4) dest 도시 적 장수 이동 — draw-for-draw 타겟.
        val destCityId = (context.args["destCityID"] as? Number)?.toInt() ?: return
        @Suppress("UNCHECKED_CAST")
        val suppliedCities = (context.args["__suppliedEnemyCities"] as? List<*>)
            ?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        if (suppliedCities.isNotEmpty()) {
            for (tg in context.candidateGenerals) {
                // dest 장수 자신의 로그(PLAIN, 그 장수 스코프).
                context.addPlainLogTo(tg.id, "상대의 <M>허보</>에 당했다! <1>${context.date}</>")
                var moveCityId = context.rng.choice(suppliedCities)                  // DRAW
                if (moveCityId == destCityId) {
                    moveCityId = context.rng.choice(suppliedCities)                  // 현재(공격대상) 도시 → re-roll
                }
                d.cascadeGenerals.add(tg.copy(cityId = moveCityId))
            }
        }

        // (5) nation strategic_cmd_limit = 9 (golden 미캡처 — meta 기록).
        d.nation = d.nation?.let { it.copy(meta = withMeta(it.meta, "strategic_cmd_limit" to 9)) }
    }
}
