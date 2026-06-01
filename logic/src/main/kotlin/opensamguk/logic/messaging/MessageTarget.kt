package opensamguk.logic.messaging

/**
 * The sender/recipient identity card carried in every message.
 *
 * PHP reference: `Message::getSrc()` / `Message::getDest()` return arrays with these fields.
 * The `color` and `icon` are the nation visual identifiers (nation.colour, nation.icon).
 */
data class MessageTarget(
    val generalId: Int,
    val generalName: String,
    val nationId: Int,
    val nationName: String,
    val color: String,
    val icon: String,
)

/** Type alias for the optional action/action-data map embedded in a message payload. */
typealias MessageOption = Map<String, Any?>
