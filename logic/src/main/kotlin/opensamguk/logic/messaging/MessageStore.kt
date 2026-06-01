package opensamguk.logic.messaging

/**
 * Persistence contract for the messaging subsystem.
 *
 * The actual DB implementation lives in the game-engine / infra layer (JDBC batch,
 * MessageRowMapper, etc.). The logic layer only consumes this interface via DI.
 *
 * PHP reference: `Message::send()` eventually calls `$db->insert()` on the message table.
 */
interface MessageStore {
    /** Insert a resolved [MessageRecordDraft] and return the generated message ID. */
    fun insertMessage(draft: MessageRecordDraft): Int
}
