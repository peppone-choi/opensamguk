package opensamguk.logic.war.plan

/**
 * Phase 4X-C 출병 계획 봉인(공격자) — 순수 규칙 (specs/2026-09-06-wego-field-seal-replay-vertical-slice v4.1).
 * Spring/DB/world 없음. 임계값은 **플레이어 입력**(손실 %·사기)이고 상수는 입력 범위와 스키마 버전뿐(`rules.provisional = true`).
 */
object BattlePlanRules {
    const val PROVISIONAL = true

    const val RETREAT_LOSS_PCT_MIN = 10
    const val RETREAT_LOSS_PCT_MAX = 90
    const val RETREAT_MORALE_MIN = 0
    const val RETREAT_MORALE_MAX = 100
    const val REPLAY_SCHEMA_VERSION = 1

    const val STANCE_ASSAULT = "assault"
    const val STANCE_PROBE = "probe"
    val STANCES: List<String> = listOf(STANCE_ASSAULT, STANCE_PROBE)
    val STANCE_LABELS: Map<String, String> = mapOf(STANCE_ASSAULT to "돌격", STANCE_PROBE to "탐색")
    val STANCE_DESCRIPTIONS: Map<String, String> = mapOf(
        STANCE_ASSAULT to "오늘의 출병과 같다 — 조건이 참이 될 때까지 싸운다.",
        STANCE_PROBE to "첫 접촉 페이즈 뒤 퇴각한다(퇴각은 부상 판정을 받는다).",
    )
    /** 아트보드의 나머지 태세 — 엔진 대응물이 없어 disabled + 사유로만 노출. */
    val RESERVED_STANCES: List<String> = listOf("advance", "defend", "flank")
    val RESERVED_STANCE_LABELS: Map<String, String> = mapOf("advance" to "전진", "defend" to "방어", "flank" to "우회")
    const val REASON_STANCE_RESERVED = "이 절편에서는 지원하지 않습니다."

    const val RESULT_RETREAT = "retreat"
    const val RESULT_REPELLED = "repelled"
    const val RESULT_DEFENDERS_DOWN = "defenders_down"
    const val RESULT_CONQUERED = "conquered"
    val RESULT_LABELS: Map<String, String> = mapOf(
        RESULT_RETREAT to "퇴각", RESULT_REPELLED to "수비 성공", RESULT_DEFENDERS_DOWN to "수비 격파 · 미점령", RESULT_CONQUERED to "점령",
    )
    val PLAN_STOP_LABELS: Map<String, String> = mapOf(
        PlanStop.PROBE.code to "탐색 완료", PlanStop.LOSS_PCT.code to "병력 손실 조건", PlanStop.MORALE.code to "사기 조건",
    )

    const val REASON_INPUT = "올바르지 않은 입력입니다."
    const val REASON_NO_TARGET = "목표를 찾을 수 없습니다."
    const val REASON_OWN_CITY = "아군 도시입니다."
    const val REASON_SEALED = "봉인된 계획입니다."
    const val REASON_NO_PLAN = "계획이 없습니다."

    sealed interface SaveInput {
        data class Ok(val targetCityId: Int, val stance: String, val retreatLossPct: Int?, val retreatMoraleBelow: Int?) : SaveInput
        data class Denied(val reason: String) : SaveInput
    }

    /** ③ 입력 게이트 — stance ∉ 2종 / pct ∉ [MIN,MAX] / morale ∉ [0,100] / 목표 도시 없음 → 입력 오류. */
    fun saveInput(targetCityId: Int?, stance: String?, retreatLossPct: Int?, retreatMoraleBelow: Int?): SaveInput {
        if (targetCityId == null || targetCityId <= 0) return SaveInput.Denied(REASON_INPUT)
        if (stance == null || stance !in STANCES) return SaveInput.Denied(REASON_INPUT)
        if (retreatLossPct != null && retreatLossPct !in RETREAT_LOSS_PCT_MIN..RETREAT_LOSS_PCT_MAX) return SaveInput.Denied(REASON_INPUT)
        if (retreatMoraleBelow != null && retreatMoraleBelow !in RETREAT_MORALE_MIN..RETREAT_MORALE_MAX) return SaveInput.Denied(REASON_INPUT)
        return SaveInput.Ok(targetCityId, stance, retreatLossPct, retreatMoraleBelow)
    }

    /** ④ 저장 상태 게이트 — 1 도시 없음 → 2 아군 도시 → 3 같은 (장수, 도시) 의 미소비 계획이 봉인됨. */
    fun saveDeny(targetExists: Boolean, targetNationId: Int?, myNationId: Int, existingSealed: Boolean): String? = when {
        !targetExists -> REASON_NO_TARGET
        targetNationId != null && myNationId != 0 && targetNationId == myNationId -> REASON_OWN_CITY
        existingSealed -> REASON_SEALED
        else -> null
    }

    /** ④ 봉인 게이트 — 1 내 미소비 계획 아님 → 2 이미 봉인. */
    fun sealDeny(planMineUnresolved: Boolean, sealed: Boolean): String? = when {
        !planMineUnresolved -> REASON_NO_PLAN
        sealed -> REASON_SEALED
        else -> null
    }

    /** ④ 삭제 게이트 — 초안만 삭제된다. */
    fun deleteDeny(planMineUnresolved: Boolean, sealed: Boolean): String? = sealDeny(planMineUnresolved, sealed)

    /**
     * 페이즈 사이의 계획 정지 판정(draw 0, spec §5). 여러 조건이 동시면 표기 순 probe → loss → morale.
     * `phaseIndex` = 방금 끝난 페이즈 수(1부터). `crewNow`·`atmosNow` 는 그 페이즈가 끝난 뒤의 공격자 상태.
     */
    fun plannedStop(plan: SealedBattlePlan, phaseIndex: Int, crewBefore: Int, crewNow: Int, atmosNow: Double): PlanStop? {
        if (plan.stance == STANCE_PROBE && phaseIndex >= 1) return PlanStop.PROBE
        val pct = plan.retreatLossPct
        if (pct != null && crewNow <= crewBefore * (100 - pct) / 100) return PlanStop.LOSS_PCT
        val morale = plan.retreatMoraleBelow
        if (morale != null && atmosNow < morale) return PlanStop.MORALE
        return null
    }

    /**
     * `result` 단일 규칙(spec §5 P1, 훅 이벤트 기준): ① 점령 → ② 퇴각(자연·계획) → ③ 마지막 페이즈에 마지막 상대가 무너짐(미점령)
     * → ④ 페이즈 소진(마지막 상대 생존 — 가장 흔한 결말, 비공성 성 재정비 포함).
     */
    fun resultOf(conquered: Boolean, retreat: Boolean, lastDefenderDown: Boolean): String = when {
        conquered -> RESULT_CONQUERED
        retreat -> RESULT_RETREAT
        lastDefenderDown -> RESULT_DEFENDERS_DOWN
        else -> RESULT_REPELLED
    }
}

/** 계획 조건이 멈춘 종류 — `battle_replay.plan_stop` CHECK 값. */
enum class PlanStop(val code: String) { PROBE("probe"), LOSS_PCT("loss_pct"), MORALE("morale") }

/** 봉인·미소비 계획의 해결 시점 스냅샷 — `BattleCommandContext.sealedPlans` 값(키 = 목표 도시). */
data class SealedBattlePlan(
    val id: Int,
    val targetCityId: Int,
    val stance: String,
    val retreatLossPct: Int?,
    val retreatMoraleBelow: Int?,
)
