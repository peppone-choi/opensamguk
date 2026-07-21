package opensamguk.infra.read

import opensamguk.common.world.WorldId
import opensamguk.infra.entity.MessageEntity
import opensamguk.logic.message.MessageType
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import org.springframework.data.repository.Repository as SpringDataRepository

/**
 * JPA read repository for `message` — process-world scoped (OPENSAM-127).
 * Write path remains MessageRowMapper + JdbcFlushExecutor.
 */
interface MessageRawRepository : SpringDataRepository<MessageEntity, Int> {
    @Query(value = "SELECT COALESCE(MAX(id), 0) FROM message WHERE world_id = :worldId", nativeQuery = true)
    fun findMaxId(@Param("worldId") worldId: Int): Int

    fun findByWorldIdAndMailboxOrderById(worldId: Int, mailbox: Int): List<MessageEntity>

    fun findByWorldIdAndMailbox(worldId: Int, mailbox: Int): List<MessageEntity>

    fun findByWorldIdAndMailboxAndValidUntilAfter(worldId: Int, mailbox: Int, now: Instant): List<MessageEntity>

    fun findByWorldIdAndMailboxAndType(worldId: Int, mailbox: Int, type: MessageType): List<MessageEntity>

    fun findByWorldIdAndMailboxAndTypeAndValidUntilAfterOrderByIdDesc(
        worldId: Int,
        mailbox: Int,
        type: MessageType,
        now: Instant,
    ): List<MessageEntity>

    fun findTop15ByWorldIdAndMailboxAndTypeAndValidUntilAfterAndIdLessThanOrderByIdDesc(
        worldId: Int,
        mailbox: Int,
        type: MessageType,
        now: Instant,
        toSeq: Int,
    ): List<MessageEntity>

    fun findByWorldIdAndId(worldId: Int, id: Int): MessageEntity?
}

/**
 * Process-world facade. [opensamguk.world-id] / OPENSAMGUK_WORLD_ID must be a positive integer
 * (same contract as game-api process world). Engine tests set the property via DynamicPropertySource.
 */
@Repository
class MessageRepository(
    private val raw: MessageRawRepository,
    @Value("\${opensamguk.world-id:0}") opensamgukWorldId: Int,
    @Value("\${OPENSAMGUK_WORLD_ID:0}") envWorldId: Int,
) {
    private val worldId: WorldId = WorldId(
        when {
            opensamgukWorldId > 0 -> opensamgukWorldId
            envWorldId > 0 -> envWorldId
            else -> error("opensamguk.world-id or OPENSAMGUK_WORLD_ID must be a positive integer")
        },
    )

    fun findMaxId(): Int = raw.findMaxId(worldId.value)

    fun findByMailboxOrderById(mailbox: Int): List<MessageEntity> =
        raw.findByWorldIdAndMailboxOrderById(worldId.value, mailbox)

    fun findByMailbox(mailbox: Int): List<MessageEntity> =
        raw.findByWorldIdAndMailbox(worldId.value, mailbox)

    fun findByMailboxAndValidUntilAfter(mailbox: Int, now: Instant): List<MessageEntity> =
        raw.findByWorldIdAndMailboxAndValidUntilAfter(worldId.value, mailbox, now)

    fun findByMailboxAndType(mailbox: Int, type: MessageType): List<MessageEntity> =
        raw.findByWorldIdAndMailboxAndType(worldId.value, mailbox, type)

    fun findByMailboxAndTypeAndValidUntilAfterOrderByIdDesc(
        mailbox: Int,
        type: MessageType,
        now: Instant,
    ): List<MessageEntity> =
        raw.findByWorldIdAndMailboxAndTypeAndValidUntilAfterOrderByIdDesc(
            worldId.value, mailbox, type, now,
        )

    fun findTop15ByMailboxAndTypeAndValidUntilAfterAndIdLessThanOrderByIdDesc(
        mailbox: Int,
        type: MessageType,
        now: Instant,
        toSeq: Int,
    ): List<MessageEntity> =
        raw.findTop15ByWorldIdAndMailboxAndTypeAndValidUntilAfterAndIdLessThanOrderByIdDesc(
            worldId.value, mailbox, type, now, toSeq,
        )

    fun findById(id: Int): java.util.Optional<MessageEntity> =
        java.util.Optional.ofNullable(raw.findByWorldIdAndId(worldId.value, id))
}
