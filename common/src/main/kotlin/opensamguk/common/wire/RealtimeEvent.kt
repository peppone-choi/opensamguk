package opensamguk.common.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Faithful port of `realtime/types.ts:1-20` (RealtimeEvent union + MessageTypeKey).
 */
@Serializable
enum class MessageTypeKey {
    @SerialName("public") PUBLIC,
    @SerialName("private") PRIVATE,
    @SerialName("national") NATIONAL,
    @SerialName("diplomacy") DIPLOMACY,
}

@Serializable
sealed class RealtimeEvent {
    abstract val type: String

    @Serializable
    @SerialName("turnCompleted")
    data class TurnCompleted(
        val at: String,
        val lastTurnTime: String,
    ) : RealtimeEvent() {
        override val type: String get() = "turnCompleted"
    }

    @Serializable
    @SerialName("messageCreated")
    data class MessageCreated(
        val at: String,
        val mailbox: Int,
        val msgType: MessageTypeKey,
        val messageId: Int,
        val senderId: Int,
    ) : RealtimeEvent() {
        override val type: String get() = "messageCreated"
    }
}
