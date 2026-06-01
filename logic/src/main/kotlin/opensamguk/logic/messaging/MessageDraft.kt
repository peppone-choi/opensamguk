package opensamguk.logic.messaging

import java.time.LocalDateTime

/**
 * A fully-assembled message ready for routing and persistence.
 *
 * PHP reference: `Message::buildFromArray()` consumes the fields that mirror this structure.
 * The `option` map carries action-specific data (e.g. diplomatic action type, scout data).
 */
data class MessageDraft(
    val msgType: MessageType,
    val src: MessageTarget,
    val dest: MessageTarget,
    val text: String,
    val time: LocalDateTime,
    val validUntil: LocalDateTime,
    val option: MessageOption? = null,
)

/**
 * The JSON-serializable payload stored in the DB `payload` column.
 */
data class MessagePayload(
    val src: MessageTarget,
    val dest: MessageTarget,
    val text: String,
    val option: MessageOption? = null,
)

/**
 * The record-level draft handed to [MessageStore.insertMessage].
 * All routing (mailbox, srcId, destId) has been resolved.
 */
data class MessageRecordDraft(
    val mailbox: Int,
    val msgType: MessageType,
    val srcId: Int,
    val destId: Int,
    val time: LocalDateTime,
    val validUntil: LocalDateTime,
    val payload: MessagePayload,
)
