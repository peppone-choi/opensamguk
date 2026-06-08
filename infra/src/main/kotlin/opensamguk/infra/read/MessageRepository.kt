package opensamguk.infra.read

import opensamguk.infra.entity.MessageEntity
import opensamguk.logic.message.MessageType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * JPA read repository for `message` table.
 *
 * P7 read API uses this for mailbox queries (inbox, national, public, diplomacy).
 * The write path (daemon flush) goes through [MessageRowMapper] + [JdbcFlushExecutor].
 *
 * `mailbox` semantics: 9999 == public, >= 9000 national (nation_id + 9000), < 9000 private (general_id).
 */
@Repository
interface MessageRepository : JpaRepository<MessageEntity, Int> {

    /** All messages in a mailbox, ordered by id (newest last — PHP default). */
    fun findByMailboxOrderById(mailbox: Int): List<MessageEntity>

    /** Convenience: all messages in a mailbox (any order). */
    fun findByMailbox(mailbox: Int): List<MessageEntity>

    /** Unread messages: `valid_until > now()` (the PHP "not expired" check). */
    fun findByMailboxAndValidUntilAfter(mailbox: Int, now: Instant): List<MessageEntity>

    /** Messages by type within a mailbox. */
    fun findByMailboxAndType(mailbox: Int, type: MessageType): List<MessageEntity>

    /**
     * D7 GetRecentMessage — messages in a mailbox of a given type, valid and ordered by id DESC.
     * PHP `getMessagesFromMailBox`: `valid_until > now`, `ORDER BY id DESC`, `LIMIT`.
     */
    fun findByMailboxAndTypeAndValidUntilAfterOrderByIdDesc(
        mailbox: Int,
        type: MessageType,
        now: Instant,
    ): List<MessageEntity>

    /**
     * D8 GetOldMessage — messages in a mailbox of a given type, valid, id < toSeq, ordered by id DESC.
     * PHP `getMessagesFromMailBoxOld`: `valid_until > now`, `id < toSeq`, `ORDER BY id DESC`, `LIMIT`.
     */
    fun findTop15ByMailboxAndTypeAndValidUntilAfterAndIdLessThanOrderByIdDesc(
        mailbox: Int,
        type: MessageType,
        now: Instant,
        toSeq: Int,
    ): List<MessageEntity>
}
