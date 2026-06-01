package opensamguk.logic.messaging

/**
 * Send a message: persist the receiver copy and optionally a sender copy.
 *
 * PHP `Message::send()` logic:
 *  1. Resolve receiver mailbox → insert receiver message (always).
 *  2. Resolve sender mailbox → if not null and not same as receiver, insert sender copy.
 *  3. For diplomacy type: sender copy has `action` stripped from option (수신자만 action 볼 수 있음).
 *  4. Sender copy includes `receiverMessageID` in its option (역참조).
 *
 * @param store The [MessageStore] implementation (injected at the engine layer).
 * @param draft The fully-assembled [MessageDraft].
 * @param sendDestOnly If true, skip the sender copy (송신 복사본 생략).
 * @return [SendResult] containing the inserted message IDs.
 */
fun sendMessage(
    store: MessageStore,
    draft: MessageDraft,
    sendDestOnly: Boolean = false,
): SendResult {
    // --- Receiver message (always) ---
    val receiverMailbox = resolveReceiverMailbox(draft)
    val receiverPayload = MessagePayload(
        src = draft.src,
        dest = draft.dest,
        text = draft.text,
        option = draft.option,
    )
    val receiverRecord = MessageRecordDraft(
        mailbox = receiverMailbox,
        msgType = draft.msgType,
        srcId = resolveRecordSrcId(draft),
        destId = resolveRecordDestId(draft),
        time = draft.time,
        validUntil = draft.validUntil,
        payload = receiverPayload,
    )
    val receiverId = store.insertMessage(receiverRecord)

    // --- Sender copy (optional) ---
    if (sendDestOnly) {
        return SendResult(receiverId = receiverId, senderId = null)
    }

    val senderMailbox = resolveSenderMailbox(draft)
    if (senderMailbox == null || senderMailbox == receiverMailbox) {
        return SendResult(receiverId = receiverId, senderId = null)
    }

    // For diplomacy: strip the action field from the sender copy
    val senderOption = when (draft.msgType) {
        MessageType.DIPLOMACY -> {
            draft.option?.toMutableMap()?.apply { remove("action") }?.toMap()
        }
        else -> draft.option
    }?.let {
        val mutable = it.toMutableMap()
        mutable["receiverMessageID"] = receiverId
        mutable.toMap()
    } ?: mapOf("receiverMessageID" to receiverId)

    val senderPayload = MessagePayload(
        src = draft.src,
        dest = draft.dest,
        text = draft.text,
        option = senderOption,
    )
    val senderRecord = MessageRecordDraft(
        mailbox = senderMailbox,
        msgType = draft.msgType,
        srcId = resolveRecordSrcId(draft),
        destId = resolveRecordDestId(draft),
        time = draft.time,
        validUntil = draft.validUntil,
        payload = senderPayload,
    )
    val senderId = store.insertMessage(senderRecord)

    return SendResult(receiverId = receiverId, senderId = senderId)
}

/**
 * Result of a [sendMessage] call.
 *
 * @property receiverId The ID of the inserted receiver message (always present).
 * @property senderId   The ID of the inserted sender copy (null when skipped).
 */
data class SendResult(
    val receiverId: Int,
    val senderId: Int? = null,
)
