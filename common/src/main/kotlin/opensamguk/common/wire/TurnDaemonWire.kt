package opensamguk.common.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Value types faithful to `turnDaemon/types.ts:1-41`.
 * Enums serialize lowercase via `@SerialName` to match the TS string-literal unions.
 */

@Serializable
enum class TurnDaemonState {
    @SerialName("idle") IDLE,
    @SerialName("running") RUNNING,
    @SerialName("flushing") FLUSHING,
    @SerialName("paused") PAUSED,
    @SerialName("stopping") STOPPING,
}

@Serializable
enum class RunReason {
    @SerialName("schedule") SCHEDULE,
    @SerialName("manual") MANUAL,
    @SerialName("poke") POKE,
}

@Serializable
data class TurnRunBudget(
    val budgetMs: Long,
    val maxGenerals: Int,
    val catchUpCap: Int,
)

@Serializable
data class TurnCheckpoint(
    val turnTime: String,
    val generalId: Int? = null,
    val year: Int,
    val month: Int,
)

@Serializable
data class TurnRunResult(
    val lastTurnTime: String,
    val processedGenerals: Int,
    val processedTurns: Int,
    val durationMs: Long,
    val partial: Boolean,
    val checkpoint: TurnCheckpoint? = null,
    val deletedGenerals: List<Int>? = null,
    val deletedTroops: List<Int>? = null,
)

@Serializable
data class TurnDaemonStatus(
    val state: TurnDaemonState,
    val running: Boolean,
    val paused: Boolean,
    val lastError: String? = null,
    val lastRunAt: String? = null,
    val lastDurationMs: Long? = null,
    val lastTurnTime: String? = null,
    val nextTurnTime: String? = null,
    val pendingReason: RunReason? = null,
    val queueDepth: Int,
    val checkpoint: TurnCheckpoint? = null,
)
