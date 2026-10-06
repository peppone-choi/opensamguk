package opensamguk.gameapi.battle.realtime

import java.time.Instant

data class BattleActiveSourceKey(val kind: String, val sourceId: String)
data class BattleActiveMySeat(val sourceKeys: List<BattleActiveSourceKey>)

/** Nullable fields have no producer on the current main; the reason is part of the wire proposal. */
data class BattleActiveEntry(
    val battleId: String,
    val worldId: String,
    val kind: String,
    val sourcePhase: String,
    val phase: String,
    val joinDeadlineAt: Instant?,
    val observedAt: Instant,
    val mySeat: BattleActiveMySeat,
    val pacingMode: String? = null,
    val pacingModeUnavailableReason: String = "SOURCE_NOT_AVAILABLE",
    val controller: String? = null,
    val controllerUnavailableReason: String = "SOURCE_NOT_AVAILABLE",
    val place: String? = null,
    val placeUnavailableReason: String = "SOURCE_NOT_AVAILABLE",
    val sides: List<String>? = null,
    val sidesUnavailableReason: String = "SOURCE_NOT_AVAILABLE",
    val replayId: String? = null,
    val replayIdUnavailableReason: String = "SOURCE_NOT_AVAILABLE",
)

data class BattleActiveErrorDetail(val code: String, val message: String)
data class BattleActiveError(val error: BattleActiveErrorDetail)
