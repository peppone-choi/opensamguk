package opensamguk.logic.message

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T0.5 — Message / MessageTarget value-object parity (PHP `Message.php` + `MessageTarget.php`).
 */
class MessageTest {

    @AfterTest fun reset() = Message.clearBuilders()

    private fun target(id: Int, name: String, nationId: Int, nation: String, color: String = "#fff") =
        MessageTarget(id, name, nationId, nation, color, icon = "ic")

    // ---- MessageTarget --------------------------------------------------------------------------

    @Test
    fun `toArray and toArrayLight preserve the load-bearing key order`() {
        val t = target(10, "관우", 1, "촉", "#0f0")
        assertEquals(listOf("id", "name", "nation_id", "nation", "color", "icon"), t.toArray().keys.toList())
        assertEquals(listOf("name", "nation", "color", "icon"), t.toArrayLight().keys.toList())
    }

    @Test
    fun `buildFromArray applies the 재야 fallback when nation_id is falsy`() {
        val t = MessageTarget.buildFromArray(linkedMapOf("id" to 5, "name" to "방랑객"))
        assertEquals(0, t.nationId)
        assertEquals("재야", t.nationName)
        assertEquals("#000000", t.color)
    }

    @Test
    fun `buildSystemTarget matches PHP (0, '', 0, System, #000000)`() {
        val s = MessageTarget.buildSystemTarget()
        assertEquals(0, s.generalId)
        assertEquals("System", s.nationName)
        assertEquals("#000000", s.color)
    }

    // ---- setSentInfo state machine --------------------------------------------------------------

    private fun msg(type: MessageType, src: MessageTarget, dest: MessageTarget, option: Map<String, Any?>? = emptyMap()) =
        Message(type, src, dest, "본문", "2026-05-31 00:00:00", "9999-12-31 23:59:59", option)

    @Test
    fun `private setSentInfo marks the receiver mailbox inbox and the sender outbox`() {
        val src = target(1, "A", 1, "촉"); val dest = target(2, "B", 2, "위")
        val inbox = msg(MessageType.PRIVATE, src, dest).setSentInfo(dest.generalId, 100)
        assertTrue(inbox.isInboxMail)
        val outbox = msg(MessageType.PRIVATE, src, dest).setSentInfo(src.generalId, 101)
        assertTrue(!outbox.isInboxMail)
    }

    @Test
    fun `invalid mailbox throws`() {
        val src = target(1, "A", 1, "촉"); val dest = target(2, "B", 2, "위")
        assertFailsWith<IllegalArgumentException> { msg(MessageType.PRIVATE, src, dest).setSentInfo(0, 1) }
        assertFailsWith<IllegalArgumentException> { msg(MessageType.PRIVATE, src, dest).setSentInfo(10001, 1) }
    }

    // ---- send: two-row receiver-before-sender ----------------------------------------------------

    @Test
    fun `private send emits receiver row BEFORE sender row`() {
        val src = target(1, "A", 1, "촉"); val dest = target(2, "B", 2, "위")
        val drafts = msg(MessageType.PRIVATE, src, dest).send()
        assertEquals(2, drafts.size)
        assertEquals(MessageRowDraft.Row.RECEIVER, drafts[0].whichRow)
        assertEquals(dest.generalId, drafts[0].mailbox)
        assertEquals(MessageRowDraft.Row.SENDER, drafts[1].whichRow)
        assertEquals(src.generalId, drafts[1].mailbox)
    }

    @Test
    fun `sendDestOnly emits only the receiver row`() {
        val src = MessageTarget.buildSystemTarget(); val dest = target(2, "B", 2, "위")
        val drafts = msg(MessageType.PRIVATE, src, dest).send(sendDestOnly = true)
        assertEquals(1, drafts.size)
        assertEquals(MessageRowDraft.Row.RECEIVER, drafts.single().whichRow)
    }

    @Test
    fun `diplomacy sender row nulls the option when it carries an action`() {
        val src = target(1, "A", 1, "촉"); val dest = target(2, "B", 2, "위")
        val drafts = msg(MessageType.DIPLOMACY, src, dest, option = linkedMapOf("action" to "agree")).send()
        assertEquals(2, drafts.size)
        // both mailboxes are nation mailboxes; receiver keeps the option, sender nulls it.
        assertEquals(dest.nationId + Mailbox.NATIONAL_BASE, drafts[0].mailbox)
        assertTrue(drafts[0].option?.containsKey("action") == true)
        assertEquals(src.nationId + Mailbox.NATIONAL_BASE, drafts[1].mailbox)
        assertNull(drafts[1].option, "the diplomacy sender row nulls an action-bearing option")
    }

    @Test
    fun `double send throws (sendCnt guard)`() {
        val m = msg(MessageType.PRIVATE, target(1, "A", 1, "촉"), target(2, "B", 2, "위"))
        m.send()
        assertFailsWith<IllegalStateException> { m.send() }
    }

    // ---- invalidate -----------------------------------------------------------------------------

    @Test
    fun `invalidate hideMsg hides via validUntil and non-hide rewrites text`() {
        val m1 = msg(MessageType.PRIVATE, target(1, "A", 1, "촉"), target(2, "B", 2, "위"))
        m1.invalidate(hideMsg = true)
        assertEquals("2000-12-31 00:00:00", m1.validUntil)
        assertEquals(true, m1.msgOption!!["invalid"])

        val m2 = msg(MessageType.PRIVATE, target(1, "A", 1, "촉"), target(2, "B", 2, "위"),
            option = linkedMapOf("receiverMessageID" to 5))
        m2.invalidate(hideMsg = false)
        assertEquals("삭제된 메시지입니다.", m2.msg)
        assertEquals("본문", m2.msgOption!!["originalText"])
    }

    // ---- buildFromArray polymorphic dispatch ----------------------------------------------------

    @Test
    fun `buildFromArray falls back to base Message for an unregistered tag`() {
        val row = linkedMapOf<String, Any?>("id" to 7, "type" to "private", "mailbox" to 2,
            "time" to "2026-05-31 00:00:00", "valid_until" to "9999-12-31 23:59:59")
        val body = linkedMapOf<String, Any?>(
            "src" to linkedMapOf("id" to 1, "name" to "A", "nation_id" to 1, "nation" to "촉", "color" to "#fff"),
            "dest" to linkedMapOf("id" to 2, "name" to "B", "nation_id" to 2, "nation" to "위", "color" to "#fff"),
            "text" to "x", "option" to linkedMapOf("action" to "scout"),
        )
        // 'scout' tag registered nowhere → base Message (does not crash).
        val m = Message.buildFromArray(row, body)
        assertEquals(Message::class, m::class)
        assertFailsWith<NotInheritedMethodException> { m.agreeMessage(2) }
    }

    @Test
    fun `buildFromArray dispatches to a registered subclass factory`() {
        Message.registerBuilder("scout") { a -> object : Message(a.msgType, a.src, a.dest, a.text, a.date, a.validUntil, a.option) {
            override fun agreeMessage(receiverId: Int): Int = 42
        } }
        val row = linkedMapOf<String, Any?>("id" to 7, "type" to "private", "mailbox" to 2,
            "time" to "2026-05-31 00:00:00", "valid_until" to "9999-12-31 23:59:59")
        val body = linkedMapOf<String, Any?>(
            "src" to linkedMapOf("id" to 1, "name" to "A", "nation_id" to 1, "nation" to "촉", "color" to "#fff"),
            "dest" to linkedMapOf("id" to 2, "name" to "B", "nation_id" to 2, "nation" to "위", "color" to "#fff"),
            "text" to "x", "option" to linkedMapOf("action" to "scout"),
        )
        val m = Message.buildFromArray(row, body)
        assertEquals(42, m.agreeMessage(2))
    }
}
