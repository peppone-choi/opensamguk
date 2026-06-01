package opensamguk.logic.messaging

/**
 * The action type embedded in a diplomatic message's option.
 *
 * PHP reference: `DiplomaticMessage::TYPE_NO_AGGRESSION`, `TYPE_CANCEL_NA`, `TYPE_STOP_WAR`.
 */
enum class DiplomaticActionType(val code: String) {
    NO_AGGRESSION("no_aggression"),
    CANCEL_NA("cancel_na"),
    STOP_WAR("stop_war"),
}

/**
 * Domain model for a diplomatic message.
 *
 * In the PHP flow, `DiplomaticMessage::agreeMessage()` validates the message then executes
 * the corresponding nation command (che_불가침수락, che_종전수락, che_불가침파기수락).
 *
 * The Kotlin version is a data class (not a polymorphic entity); the accept logic is handled
 * by the engine layer that wires [MessageStore] + [CommandRegistry] together.
 *
 * @property id The message ID (assigned after DB insert).
 * @property fromNationId Source nation.
 * @property toNationId   Destination nation.
 * @property fromGeneralId Source general (the diplomat / chief).
 * @property actionType   Which diplomatic action this message represents.
 * @property args         Additional arguments (destNationId, year, month, etc.).
 * @property validUntil   Expiration timestamp (yearMonth integer or absolute timestamp).
 * @property accepted     Whether the message has already been accepted.
 */
data class DiplomaticMessage(
    val id: Int,
    val fromNationId: Int,
    val toNationId: Int,
    val fromGeneralId: Int,
    val actionType: DiplomaticActionType,
    val args: Map<String, Any?>,
    val validUntil: Int,
    val accepted: Boolean = false,
) {
    /**
     * The nation-command key triggered when this message is accepted.
     */
    val acceptCommandKey: String = when (actionType) {
        DiplomaticActionType.NO_AGGRESSION -> "che_불가침수락"
        DiplomaticActionType.CANCEL_NA -> "che_불가침파기수락"
        DiplomaticActionType.STOP_WAR -> "che_종전수락"
    }

    companion object {
        /**
         * Build a [DiplomaticMessage] from raw DB/map data.
         * Used by [MessageFactory] for the polymorphic deserialization path.
         */
        @Suppress("UNCHECKED_CAST")
        fun fromMap(raw: Map<String, Any?>): DiplomaticMessage {
            val payload = (raw["payload"] as? Map<String, Any?>) ?: emptyMap()
            val option = (payload["option"] as? Map<String, Any?>) ?: emptyMap()
            val actionCode = (option["action"] as? String) ?: "no_aggression"
            val actionType = DiplomaticActionType.entries.find { it.code == actionCode }
                ?: DiplomaticActionType.NO_AGGRESSION

            return DiplomaticMessage(
                id = (raw["id"] as? Number)?.toInt() ?: 0,
                fromNationId = (option["fromNationId"] as? Number)?.toInt() ?: 0,
                toNationId = (option["toNationId"] as? Number)?.toInt() ?: 0,
                fromGeneralId = (option["fromGeneralId"] as? Number)?.toInt() ?: 0,
                actionType = actionType,
                args = (option["args"] as? Map<String, Any?>) ?: emptyMap(),
                validUntil = (raw["valid_until"] as? Number)?.toInt() ?: 0,
                accepted = (raw["accepted"] as? Boolean) ?: false,
            )
        }
    }
}

/**
 * Result of accepting a diplomatic message.
 *
 * @property commandKey The nation command that was executed.
 * @property commandArgs The args passed to the command.
 * @property logs Any log lines produced.
 */
data class DiplomaticResult(
    val commandKey: String,
    val commandArgs: Map<String, Any?>,
    val logs: List<String>,
)
