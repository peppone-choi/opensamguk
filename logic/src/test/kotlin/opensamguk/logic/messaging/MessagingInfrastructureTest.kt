package opensamguk.logic.messaging

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the messaging infrastructure — mailbox routing, message factory, diplomatic message.
 *
 * PHP grand truth: public→9999, national/diplomacy→9000+nationId, private→generalId.
 * Sender copies have action stripped for diplomacy; null for public.
 */
class MessagingInfrastructureTest {

    private fun target(
        generalId: Int = 1,
        generalName: String = "장수",
        nationId: Int = 1,
        nationName: String = "국가",
        color: String = "#FF0000",
        icon: String = "sword",
    ) = MessageTarget(generalId, generalName, nationId, nationName, color, icon)

    // ------------------------------------------------------------------
    // Mailbox routing
    // ------------------------------------------------------------------

    @Test
    fun `resolveReceiverMailbox public returns 9999`() {
        val draft = MessageDraft(
            msgType = MessageType.PUBLIC,
            src = target(),
            dest = target(nationId = 2),
            text = "공지",
            time = LocalDateTime.now(),
            validUntil = LocalDateTime.now().plusDays(1),
        )
        assertEquals(9999, resolveReceiverMailbox(draft))
    }

    @Test
    fun `resolveReceiverMailbox national returns 9000 plus nationId`() {
        val draft = MessageDraft(
            msgType = MessageType.NATIONAL,
            src = target(),
            dest = target(nationId = 3),
            text = "국가방송",
            time = LocalDateTime.now(),
            validUntil = LocalDateTime.now().plusDays(1),
        )
        assertEquals(9003, resolveReceiverMailbox(draft))
    }

    @Test
    fun `resolveReceiverMailbox diplomacy returns 9000 plus dest nationId`() {
        val draft = MessageDraft(
            msgType = MessageType.DIPLOMACY,
            src = target(nationId = 1),
            dest = target(nationId = 5),
            text = "불가침제의",
            time = LocalDateTime.now(),
            validUntil = LocalDateTime.now().plusMonths(6),
        )
        assertEquals(9005, resolveReceiverMailbox(draft))
    }

    @Test
    fun `resolveReceiverMailbox private returns dest generalId`() {
        val draft = MessageDraft(
            msgType = MessageType.PRIVATE,
            src = target(generalId = 1),
            dest = target(generalId = 42),
            text = "쪽지",
            time = LocalDateTime.now(),
            validUntil = LocalDateTime.now().plusDays(7),
        )
        assertEquals(42, resolveReceiverMailbox(draft))
    }

    // ------------------------------------------------------------------
    // Sender mailbox
    // ------------------------------------------------------------------

    @Test
    fun `resolveSenderMailbox public returns null`() {
        val draft = MessageDraft(
            msgType = MessageType.PUBLIC,
            src = target(),
            dest = target(nationId = 2),
            text = "공지",
            time = LocalDateTime.now(),
            validUntil = LocalDateTime.now().plusDays(1),
        )
        assertNull(resolveSenderMailbox(draft))
    }

    @Test
    fun `resolveSenderMailbox diplomacy always returns national mailbox of src`() {
        val draft = MessageDraft(
            msgType = MessageType.DIPLOMACY,
            src = target(nationId = 1),
            dest = target(nationId = 1), // same nation
            text = "외교",
            time = LocalDateTime.now(),
            validUntil = LocalDateTime.now().plusMonths(6),
        )
        // diplomacy: always returns 9000 + src.nationId even if same nation
        assertEquals(9001, resolveSenderMailbox(draft))
    }

    @Test
    fun `resolveSenderMailbox private same general returns null`() {
        val draft = MessageDraft(
            msgType = MessageType.PRIVATE,
            src = target(generalId = 5),
            dest = target(generalId = 5),
            text = "자신에게",
            time = LocalDateTime.now(),
            validUntil = LocalDateTime.now().plusDays(1),
        )
        assertNull(resolveSenderMailbox(draft))
    }

    @Test
    fun `resolveSenderMailbox private different general returns src generalId`() {
        val draft = MessageDraft(
            msgType = MessageType.PRIVATE,
            src = target(generalId = 1),
            dest = target(generalId = 7),
            text = "쪽지",
            time = LocalDateTime.now(),
            validUntil = LocalDateTime.now().plusDays(1),
        )
        assertEquals(1, resolveSenderMailbox(draft))
    }

    // ------------------------------------------------------------------
    // Mailbox constants
    // ------------------------------------------------------------------

    @Test
    fun `MailboxConstants national computes correctly`() {
        assertEquals(9001, MailboxConstants.national(1))
        assertEquals(9010, MailboxConstants.national(10))
        assertEquals(9999, MailboxConstants.national(999))
    }

    @Test
    fun `MailboxConstants validation`() {
        assertTrue(MailboxConstants.isValid(1))
        assertTrue(MailboxConstants.isValid(9999))
        assertTrue(!MailboxConstants.isValid(0))
        assertTrue(!MailboxConstants.isValid(10000))
    }

    // ------------------------------------------------------------------
    // Record ID resolution
    // ------------------------------------------------------------------

    @Test
    fun `resolveRecordSrcId public returns src generalId`() {
        val draft = MessageDraft(
            msgType = MessageType.PUBLIC,
            src = target(generalId = 42),
            dest = target(),
            text = "공지",
            time = LocalDateTime.now(),
            validUntil = LocalDateTime.now(),
        )
        assertEquals(42, resolveRecordSrcId(draft))
    }

    @Test
    fun `resolveRecordSrcId diplomacy returns 9000 plus src nationId`() {
        val draft = MessageDraft(
            msgType = MessageType.DIPLOMACY,
            src = target(nationId = 3),
            dest = target(nationId = 5),
            text = "외교",
            time = LocalDateTime.now(),
            validUntil = LocalDateTime.now(),
        )
        assertEquals(9003, resolveRecordSrcId(draft))
    }

    @Test
    fun `resolveRecordDestId public returns 9999`() {
        val draft = MessageDraft(
            msgType = MessageType.PUBLIC,
            src = target(),
            dest = target(nationId = 99),
            text = "공지",
            time = LocalDateTime.now(),
            validUntil = LocalDateTime.now(),
        )
        assertEquals(9999, resolveRecordDestId(draft))
    }

    // ------------------------------------------------------------------
    // DiplomaticMessage
    // ------------------------------------------------------------------

    @Test
    fun `DiplomaticMessage acceptCommandKey maps correctly`() {
        val na = DiplomaticMessage(
            id = 1, fromNationId = 1, toNationId = 2, fromGeneralId = 10,
            actionType = DiplomaticActionType.NO_AGGRESSION,
            args = mapOf("destNationID" to 2, "year" to 2025, "month" to 6),
            validUntil = 202506,
        )
        assertEquals("che_불가침수락", na.acceptCommandKey)

        val cancel = DiplomaticMessage(
            id = 2, fromNationId = 1, toNationId = 2, fromGeneralId = 10,
            actionType = DiplomaticActionType.CANCEL_NA,
            args = mapOf("destNationID" to 2),
            validUntil = 202506,
        )
        assertEquals("che_불가침파기수락", cancel.acceptCommandKey)

        val stopWar = DiplomaticMessage(
            id = 3, fromNationId = 1, toNationId = 2, fromGeneralId = 10,
            actionType = DiplomaticActionType.STOP_WAR,
            args = mapOf("destNationID" to 2),
            validUntil = 202506,
        )
        assertEquals("che_종전수락", stopWar.acceptCommandKey)
    }

    @Test
    fun `DiplomaticMessage fromMap parses correctly`() {
        val raw = mapOf<String, Any?>(
            "id" to 100,
            "message_type" to "diplomacy",
            "valid_until" to 202512,
            "payload" to mapOf(
                "option" to mapOf(
                    "action" to "no_aggression",
                    "fromNationId" to 1,
                    "toNationId" to 3,
                    "fromGeneralId" to 42,
                    "args" to mapOf("year" to 2025, "month" to 12),
                ),
            ),
        )
        val dm = DiplomaticMessage.fromMap(raw)
        assertEquals(100, dm.id)
        assertEquals(1, dm.fromNationId)
        assertEquals(3, dm.toNationId)
        assertEquals(42, dm.fromGeneralId)
        assertEquals(DiplomaticActionType.NO_AGGRESSION, dm.actionType)
        assertEquals(202512, dm.validUntil)
    }

    // ------------------------------------------------------------------
    // MessageFactory
    // ------------------------------------------------------------------

    @Test
    fun `MessageFactory builds diplomatic message`() {
        val raw = mapOf<String, Any?>(
            "message_type" to "diplomacy",
            "payload" to mapOf(
                "option" to mapOf(
                    "action" to "stop_war",
                    "fromNationId" to 1,
                    "toNationId" to 2,
                    "fromGeneralId" to 10,
                ),
            ),
        )
        val view = MessageFactory.buildFromArray(raw)
        assertTrue(view is MessageView.Diplomatic)
        assertEquals(DiplomaticActionType.STOP_WAR, (view as MessageView.Diplomatic).message.actionType)
    }

    @Test
    fun `MessageFactory builds plain message for unknown type`() {
        val raw = mapOf<String, Any?>(
            "message_type" to "national",
            "payload" to mapOf("text" to "hello"),
        )
        val view = MessageFactory.buildFromArray(raw)
        assertTrue(view is MessageView.Plain)
    }

    @Test
    fun `MessageFactory builds scout message`() {
        val raw = mapOf<String, Any?>(
            "message_type" to "national",
            "payload" to mapOf(
                "option" to mapOf("action" to "scout"),
            ),
        )
        val view = MessageFactory.buildFromArray(raw)
        assertTrue(view is MessageView.Scout)
    }

    // ------------------------------------------------------------------
    // In-memory MessageStore for testing
    // ------------------------------------------------------------------

    class InMemoryMessageStore : MessageStore {
        val records = mutableListOf<MessageRecordDraft>()
        private var nextId = 1

        override fun insertMessage(draft: MessageRecordDraft): Int {
            records.add(draft)
            return nextId++
        }
    }

    @Test
    fun `sendMessage stores receiver copy always`() {
        val store = InMemoryMessageStore()
        val draft = MessageDraft(
            msgType = MessageType.PRIVATE,
            src = target(generalId = 1),
            dest = target(generalId = 2),
            text = "쪽지",
            time = LocalDateTime.now(),
            validUntil = LocalDateTime.now().plusDays(1),
        )
        val result = sendMessage(store, draft)
        assertEquals(1, result.receiverId)
        assertEquals(2, store.records.size) // receiver + sender
    }

    @Test
    fun `sendMessage with sendDestOnly skips sender copy`() {
        val store = InMemoryMessageStore()
        val draft = MessageDraft(
            msgType = MessageType.DIPLOMACY,
            src = target(nationId = 1),
            dest = target(nationId = 2),
            text = "외교",
            time = LocalDateTime.now(),
            validUntil = LocalDateTime.now().plusMonths(6),
            option = mapOf("action" to "no_aggression"),
        )
        val result = sendMessage(store, draft, sendDestOnly = true)
        assertEquals(1, result.receiverId)
        assertNull(result.senderId)
        assertEquals(1, store.records.size)
    }

    @Test
    fun `sendMessage diplomacy strips action from sender copy`() {
        val store = InMemoryMessageStore()
        val draft = MessageDraft(
            msgType = MessageType.DIPLOMACY,
            src = target(nationId = 1),
            dest = target(nationId = 2),
            text = "외교제의",
            time = LocalDateTime.now(),
            validUntil = LocalDateTime.now().plusMonths(6),
            option = mapOf("action" to "no_aggression", "year" to 2025),
        )
        sendMessage(store, draft)
        assertEquals(2, store.records.size)
        // Receiver copy has action
        val receiverOption = store.records[0].payload.option
        assertNotNull(receiverOption)
        assertEquals("no_aggression", receiverOption?.get("action"))
        // Sender copy has action stripped
        val senderOption = store.records[1].payload.option
        assertNotNull(senderOption)
        assertNull(senderOption?.get("action"))
        // Sender copy has receiverMessageID
        assertEquals(1, senderOption?.get("receiverMessageID"))
    }

    @Test
    fun `sendMessage public has no sender copy`() {
        val store = InMemoryMessageStore()
        val draft = MessageDraft(
            msgType = MessageType.PUBLIC,
            src = target(generalId = 1),
            dest = target(),
            text = "공지",
            time = LocalDateTime.now(),
            validUntil = LocalDateTime.now(),
        )
        val result = sendMessage(store, draft)
        assertEquals(1, result.receiverId)
        assertNull(result.senderId)
        assertEquals(1, store.records.size)
    }
}
