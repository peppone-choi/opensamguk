package opensamguk.infra.persistence

import java.time.Instant

/** Phase 4X-C 출병 계획 flush 행(step-8i). 엔진 `BattlePlan` 모양의 infra 운반체(OperationRow 와 같은 위치). */
data class BattlePlanRow(
    val id: Int,
    val generalId: Int,
    val targetCityId: Int,
    val stance: String,
    val retreatLossPct: Int?,
    val retreatMoraleBelow: Int?,
    val sealedAt: Instant?,
    val sealedYear: Int?,
    val sealedMonth: Int?,
    val sealedPhase: Int?,
    val resolvedYear: Int?,
    val resolvedMonth: Int?,
    val resolvedPhase: Int?,
    val version: Int,
)

/** `battle_replay` INSERT 행(INSERT 전용, id 는 recorder 선할당). `columns` 는 battle_replay 컬럼을 미러링. */
data class BattleReplayInsertRow(val columns: Map<String, Any?>)
