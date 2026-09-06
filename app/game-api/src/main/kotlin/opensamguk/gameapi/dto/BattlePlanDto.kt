package opensamguk.gameapi.dto

/** Phase 4X-C `/api/my-battle-plans` · `/api/battles/replays` (spec v4.1 §6). 상수는 rules 로만, `provisional = true`. */
data class BattleStanceDto(val value: String, val label: String, val description: String, val enabled: Boolean, val reason: String?)

data class BattlePlanRulesDto(
    val stances: List<BattleStanceDto>,
    val retreatLossPctMin: Int,
    val retreatLossPctMax: Int,
    val retreatMoraleMin: Int,
    val retreatMoraleMax: Int,
    val provisional: Boolean,
)

data class BattlePlanDto(
    val id: Int,
    val targetCityId: Int,
    val targetCityName: String,
    val stance: String,
    val stanceLabel: String,
    val retreatLossPct: Int?,
    val retreatMoraleBelow: Int?,
    val sealed: Boolean,
    val sealedAt: String?,
    val sealedDate: OperationDateDto?,
    /** 소비된 계획은 목록에 없다 — 항상 false(계약 표기). */
    val resolved: Boolean,
    val version: Int,
)

data class MyBattlePlansResponse(val generalId: Int, val plans: List<BattlePlanDto>, val rules: BattlePlanRulesDto)

data class BattleReplaySummaryDto(
    val id: Int,
    val year: Int,
    val month: Int,
    val phase: Int,
    val attackerGeneralId: Int?,
    val attackerName: String,
    val attackerNationId: Int,
    val defenderCityId: Int,
    val defenderCityName: String,
    val defenderNationId: Int,
    val result: String,
    val resultLabel: String,
    val attackerDead: Int,
    val defenderDead: Int,
    val hasPlan: Boolean,
    val planStop: String?,
    val planStopLabel: String?,
    val operationId: Int?,
)

data class BattleReplayPhaseDto(
    val i: Int,
    val defId: Int,
    val def: String,
    val defKind: String,
    val contact: Boolean,
    val deadA: Int,
    val deadD: Int,
    val crewA: Int,
    val hpD: Int,
)

data class BattleReplaySettlementDto(
    val attackerCrewBefore: Int,
    val attackerCrewAfter: Int,
    val attackerDead: Int,
    val defenderDead: Int,
    val riceUsed: Int,
    val conquered: Boolean,
)

data class BattleReplayPlanDto(val stance: String?, val stanceLabel: String?, val retreatLossPct: Int?, val retreatMoraleBelow: Int?, val planStop: String?, val planStopLabel: String?, val stopAtPhase: Int?)

data class BattleReplaySeedDto(val warSeed: String, val inputHash: String, val replayHash: String, val schemaVersion: Int)

data class BattleReplayDetailResponse(
    val summary: BattleReplaySummaryDto,
    val battlePhases: List<BattleReplayPhaseDto>,
    val settlement: BattleReplaySettlementDto,
    val plan: BattleReplayPlanDto?,
    val seed: BattleReplaySeedDto,
    val operationId: Int?,
)
