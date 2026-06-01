package opensamguk.logic.messaging

/**
 * Mailbox address constants and routing helpers — faithful port of PHP message mailbox logic.
 *
 * PHP defines:
 *   - MESSAGE_MAILBOX_PUBLIC = 9999
 *   - National mailbox = 9000 + nationId
 *   - Private mailbox = generalId
 */
object MailboxConstants {
    const val PUBLIC = 9999
    const val NATIONAL_BASE = 9000

    /** Compute the national mailbox address for a given nation. */
    fun national(nationId: Int): Int = NATIONAL_BASE + nationId

    /** Validate that a mailbox code is in the valid range. */
    fun isValid(mailbox: Int): Boolean = mailbox > 0 && mailbox <= PUBLIC
}

/**
 * Resolve the receiver's mailbox address from a [MessageDraft].
 *
 * PHP `Message::resolveReceiverMailbox()`:
 *   public    → 9999
 *   national  → 9000 + dest.nationId
 *   diplomacy → 9000 + dest.nationId
 *   private   → dest.generalId
 */
fun resolveReceiverMailbox(draft: MessageDraft): Int = when (draft.msgType) {
    MessageType.PUBLIC -> MailboxConstants.PUBLIC
    MessageType.NATIONAL, MessageType.DIPLOMACY -> MailboxConstants.national(draft.dest.nationId)
    MessageType.PRIVATE -> draft.dest.generalId
}

/**
 * Resolve the sender's mailbox address for the sender-copy (송신 복사본).
 *
 * PHP `Message::resolveSenderMailbox()`:
 *   public    → null (no sender copy)
 *   private   → src.generalId (if src != dest)
 *   national  → 9000 + src.nationId (if src.nation != dest.nation)
 *   diplomacy → 9000 + src.nationId (always)
 */
fun resolveSenderMailbox(draft: MessageDraft): Int? = when (draft.msgType) {
    MessageType.PUBLIC -> null
    MessageType.PRIVATE -> if (draft.src.generalId != draft.dest.generalId) draft.src.generalId else null
    MessageType.NATIONAL -> if (draft.src.nationId != draft.dest.nationId) MailboxConstants.national(draft.src.nationId) else null
    MessageType.DIPLOMACY -> MailboxConstants.national(draft.src.nationId)
}

/**
 * Compute the record-level srcId for the message record.
 *
 * PHP `buildRecord` transform:
 *   public    → destId = 9999
 *   national  → srcId = 9000 + src.nationId
 *   diplomacy → srcId = 9000 + src.nationId
 *   private   → srcId = src.generalId
 */
fun resolveRecordSrcId(draft: MessageDraft): Int = when (draft.msgType) {
    MessageType.PUBLIC -> draft.src.generalId
    MessageType.NATIONAL, MessageType.DIPLOMACY -> MailboxConstants.national(draft.src.nationId)
    MessageType.PRIVATE -> draft.src.generalId
}

/**
 * Compute the record-level destId for the message record.
 *
 * PHP `buildRecord` transform:
 *   public    → destId = 9999
 *   national  → destId = 9000 + dest.nationId
 *   diplomacy → destId = 9000 + dest.nationId
 *   private   → destId = dest.generalId
 */
fun resolveRecordDestId(draft: MessageDraft): Int = when (draft.msgType) {
    MessageType.PUBLIC -> MailboxConstants.PUBLIC
    MessageType.NATIONAL, MessageType.DIPLOMACY -> MailboxConstants.national(draft.dest.nationId)
    MessageType.PRIVATE -> draft.dest.generalId
}
