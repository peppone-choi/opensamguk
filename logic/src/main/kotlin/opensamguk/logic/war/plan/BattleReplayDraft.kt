package opensamguk.logic.war.plan

/**
 * 봉인된 계획이 있는 전투의 리플레이 초안(spec v4.1 §5). **id 만** 싣는다 — logic `General`/`City` 에 이름이 없어
 * 엔진 후처리가 `world.getGeneralById/getCityById` 로 채운다(N2). 훅([ReplayRecordingHooks])이 페이즈를 쌓고
 * `CheChulbyeong` 이 `processWar` 반환 뒤 정산을 채운다. draw 0.
 */
class BattleReplayDraft(
    val attackerId: Int,
    val attackerNationId: Int,
    val cityId: Int,
    val defenderNationId: Int,
    val warSeed: String,
    val plan: SealedBattlePlan,
    val crewBefore: Int,
    val riceBefore: Int,
    val inputHash: String,
) {
    val phases: MutableList<ReplayPhase> = mutableListOf()
    var stop: PlanStop? = null
    var stopAtPhase: Int? = null
    var retreat: Boolean = false
    var lastDefenderDown: Boolean = false
    var deadAttacker: Int = 0
    var deadDefender: Int = 0
    var crewAfter: Int = crewBefore
    var riceUsed: Int = 0
    var conquered: Boolean = false

    fun result(): String = BattlePlanRules.resultOf(conquered, retreat, lastDefenderDown)
}

/** 페이즈 한 줄 — `defKind` = general|city, `hpD` = 수비자 `getHP()`(장수 crew / 성 hp). */
data class ReplayPhase(
    val index: Int,
    val defId: Int,
    val defKind: String,
    val contact: Boolean,
    val deadAttacker: Int,
    val deadDefender: Int,
    val crewAttacker: Int,
    val hpDefender: Int,
)
