package opensamguk.logic.messaging

/**
 * Polymorphic message factory — faithful port of PHP `Message::buildFromArray()`.
 *
 * PHP distinguishes message subclasses by type + option.action:
 *   - type === 'diplomacy' → DiplomaticMessage
 *   - option.action === 'scout' → ScoutMessage
 *   - option.action === 'raiseInvader' → RaiseInvaderMessage
 *   - else → plain Message
 *
 * The Kotlin version returns domain objects (data classes) rather than polymorphic entities
 * because the logic layer is pure and framework-agnostic.
 */
object MessageFactory {

    /**
     * Build the appropriate message domain object from a raw DB/map entry.
     *
     * @param raw The raw map (typically JSONB deserialized from the DB row).
     * @return A [MessageView] representing the message (diplomatic, scout, or plain).
     */
    fun buildFromArray(raw: Map<String, Any?>): MessageView {
        val msgType = raw["message_type"] as? String
            ?: raw["msgType"] as? String
            ?: ""

        return when (msgType) {
            "diplomacy" -> {
                val dm = DiplomaticMessage.fromMap(raw)
                MessageView.Diplomatic(dm)
            }
            else -> {
                // Check option.action for special message types
                @Suppress("UNCHECKED_CAST")
                val payload = (raw["payload"] as? Map<String, Any?>) ?: emptyMap<String, Any?>()
                @Suppress("UNCHECKED_CAST")
                val option = (payload["option"] as? Map<String, Any?>) ?: emptyMap<String, Any?>()
                when (option["action"] as? String) {
                    "scout" -> MessageView.Scout(raw)
                    "raiseInvader" -> MessageView.RaiseInvader(raw)
                    else -> MessageView.Plain(raw)
                }
            }
        }
    }
}

/**
 * Sealed union of all message views the factory can produce.
 */
sealed class MessageView {
    /** A plain (non-specialized) message. */
    data class Plain(val raw: Map<String, Any?>) : MessageView()

    /** A diplomatic proposal/acceptance/rejection message. */
    data class Diplomatic(val message: DiplomaticMessage) : MessageView()

    /** A scout report message (war/city conquest related). */
    data class Scout(val raw: Map<String, Any?>) : MessageView()

    /** A raise-invader recruitment message. */
    data class RaiseInvader(val raw: Map<String, Any?>) : MessageView()
}
