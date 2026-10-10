package opensamguk.gameapi.battle.replay

import java.time.Instant

internal enum class BattleReplayReason {
    NOT_APPLIED, PIN_UNAVAILABLE, PIN_MISMATCH,
    INPUT_MISMATCH, RESULT_MISMATCH, REPLAY_HASH_MISMATCH, UNSUPPORTED_SCHEMA,
}

/** Internal diagnostic, not a K5-09 DTO. No state in this model grants public replay availability. */
internal data class BattleReplayView(
    val reason: BattleReplayReason,
    val verification: BattleReplayVerification? = null,
) {
    val publication: String = "BLOCKED_WRITE_DEPENDENCY"
}

/** Storage-only verification. storedAppliedAt is never promoted to a production campaign ACK. */
internal data class BattleReplayVerification(
    val battleId: String,
    val resultRevision: Int,
    val storedAppliedAt: Instant,
    val replayHash: String,
    val stateHash: String,
    val inputEventCount: Int,
)
