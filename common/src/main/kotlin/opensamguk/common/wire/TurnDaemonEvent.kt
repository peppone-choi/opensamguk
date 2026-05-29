package opensamguk.common.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Faithful port of `TurnDaemonEvent` (`turnDaemon/types.ts:373-378`).
 * `commandResult` nests the dual-discriminator [TurnDaemonCommandResult].
 */
@Serializable
sealed class TurnDaemonEvent {
    abstract val type: String

    @Serializable
    @SerialName("status")
    data class Status(
        val requestId: String? = null,
        val status: TurnDaemonStatus,
    ) : TurnDaemonEvent() {
        override val type: String get() = "status"
    }

    @Serializable
    @SerialName("runStarted")
    data class RunStarted(
        val at: String,
        val reason: RunReason,
    ) : TurnDaemonEvent() {
        override val type: String get() = "runStarted"
    }

    @Serializable
    @SerialName("runCompleted")
    data class RunCompleted(
        val at: String,
        val result: TurnRunResult,
    ) : TurnDaemonEvent() {
        override val type: String get() = "runCompleted"
    }

    @Serializable
    @SerialName("runFailed")
    data class RunFailed(
        val at: String,
        val error: String,
    ) : TurnDaemonEvent() {
        override val type: String get() = "runFailed"
    }

    @Serializable
    @SerialName("commandResult")
    data class CommandResult(
        val result: TurnDaemonCommandResult,
    ) : TurnDaemonEvent() {
        override val type: String get() = "commandResult"
    }
}
