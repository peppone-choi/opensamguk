package opensamguk.engine.intake

import opensamguk.common.wire.DeleteMessageResult
import opensamguk.common.wire.SendMessageResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.GeneralAccessLog
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.util.jsonDecode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W6a 메시지 풀-사이클 테스트 — `SendMessage`/`DeleteMessage` intake 명령.
 *
 * 각 테스트는 스펙이 강제하는 데몬 경로를 실행한다:
 *   command → [MessageHandler] (검증 + 부수 효과) → [ChangeRecorder] 메시지 INSERT/INVALIDATE 채널 +
 *   (private/diplomacy) [diffGeneral] meta(newmsg=1) dirty.
 *
 * send: public(1행, R4)/national/diplomacy/private + 모든 deny 게이트.
 * delete: 본인 확인 / 5분 윈도우 / 상호 무효화 / 외교 시스템 메시지 차단 / deletable / 미존재.
 * RNG 없음 — 완전 결정적(unit-gateable).
 */
class MessageHandlerTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    // ── world / handler fixtures ──────────────────────────────────────────────────────────────────

    private fun general(
        id: Int,
        name: String,
        nationId: Int,
        officerLevel: Int = 5,
        npcState: Int = 0,
        meta: Map<String, Any?> = emptyMap(),
    ) = TurnGeneral(
        id = id, name = name, nationId = nationId, cityId = 5, troopId = 0,
        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
        officerLevel = officerLevel, npcState = npcState, turnTime = t0,
        role = GeneralRole(), meta = meta,
    )

    /**
     * 2국(촉=1, 위=2) world. me(유비, 촉, 수뇌) + 손권(오 없음 — 위 소속 군주) + 위 군주(조조, officer_level=12).
     */
    private fun world(
        generals: List<TurnGeneral>,
        accessLogs: List<GeneralAccessLog> = emptyList(),
        refreshLimit: Int = 30_000,
    ): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0,
                meta = mapOf("refreshLimit" to refreshLimit),
            ),
            generals = generals,
            accessLogs = accessLogs,
            nations = listOf(
                Nation(id = 1, name = "촉", color = "#00ff00", gold = 1000),
                Nation(id = 2, name = "위", color = "#0000ff", gold = 1000),
            ),
        ),
    )

    private fun handler(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        MessageHandler(world, recorder)

    @Suppress("UNCHECKED_CAST")
    private fun bodyOf(json: String): Map<String, Any?> = jsonDecode(json)

    // ── SEND: public (R4 — receiver 1행, sender 없음) ───────────────────────────────────────────────

    @Test
    fun `send public writes exactly one receiver row and no sender row (R4)`() {
        val me = general(1, "유비", 1)
        val world = world(listOf(me))
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 9999, text = "전체 공지"),
        )

        assertTrue((res as SendMessageResult).ok)
        assertEquals("public", res.msgType)
        // R4: 정확히 1행(receiver만). sender 사본 없음.
        val rows = recorder.createdMessages()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(9999, row.mailbox)
        assertEquals("public", row.type)
        assertEquals(1, row.srcId)
        assertEquals(9999, row.destId)
        assertEquals(res.msgID, row.id)
        // body: dest=null (public — toArray dest 미포함), text 보존.
        val body = bodyOf(row.bodyJson)
        assertNull(body["dest"])
        assertEquals("전체 공지", body["text"])
        // newmsg 미발생.
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
    }

    @Test
    fun `send public denied when penalty NoSendPublicMsg`() {
        val me = general(1, "유비", 1, meta = mapOf("penalty" to mapOf("no_send_public_msg" to true)))
        val world = world(listOf(me))
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 9999, text = "x"),
        )
        assertFalse((res as SendMessageResult).ok)
        assertEquals("공개 메세지를 보낼 수 없습니다.", res.reason)
        assertTrue(recorder.createdMessages().isEmpty())
    }

    // ── SEND: national (자국 — receiver 1행, sender 없음: src.nationID==dest.nationID) ───────────────

    @Test
    fun `send national writes the nation mailbox row`() {
        val me = general(1, "유비", 1)
        val world = world(listOf(me))
        val recorder = ChangeRecorder()
        // mailbox 9001 = 9000 + nation 1(자국). permission<4라 destNationID=자국.
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 9001, text = "국가 회의"),
        )
        assertTrue((res as SendMessageResult).ok)
        assertEquals("national", res.msgType)
        // 자국 national: receiver 1행(src.nationID===dest.nationID → sender 사본 없음).
        val rows = recorder.createdMessages()
        assertEquals(1, rows.size)
        assertEquals(9001, rows.single().mailbox)
        assertEquals("national", rows.single().type)
        // national은 newmsg 알림 없음.
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
    }

    // ── SEND: diplomacy (타국 — receiver + sender 2행, 상대국 수뇌 newmsg=1) ─────────────────────────

    @Test
    fun `send diplomacy writes receiver and sender rows and flags dest chiefs newmsg`() {
        val me = general(1, "유비", 1, meta = mapOf("permission" to 4)) // 외교권자(4)여야 타국 지정 가능.
        val caocao = general(2, "조조", 2, officerLevel = 12) // 위 군주 — diplomacy 수신 알림 대상.
        val world = world(listOf(me, caocao))
        val recorder = ChangeRecorder()
        // mailbox 9002 = 9000 + nation 2(타국). permission==4라 destNationID = 9002-9000 = 2.
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 9002, text = "동맹 제안"),
        )
        assertTrue((res as SendMessageResult).ok)
        assertEquals("diplomacy", res.msgType)
        // receiver(상대국 9002) + sender(자국 9001) 2행.
        val rows = recorder.createdMessages()
        assertEquals(2, rows.size)
        assertEquals(9002, rows[0].mailbox) // receiver(상대국).
        assertEquals(9001, rows[1].mailbox) // sender(자국).
        assertEquals("diplomacy", rows[0].type)
        // sender 행 body에 receiverMessageID back-reference.
        val senderBody = bodyOf(rows[1].bodyJson)
        @Suppress("UNCHECKED_CAST")
        val senderOpt = senderBody["option"] as Map<String, Any?>
        assertEquals(rows[0].id, (senderOpt["receiverMessageID"] as Number).toInt())
        // 상대국 군주(조조) newmsg=1.
        assertTrue(recorder.dirtyGeneralIds().contains(2))
        assertEquals(1, world.getGeneralById(2)!!.meta["newmsg"])
    }

    // ── SEND: private (receiver + sender 2행, 수신자 newmsg=1) ───────────────────────────────────────

    @Test
    fun `send private writes receiver and sender rows and flags receiver newmsg`() {
        val me = general(1, "유비", 1)
        val dest = general(7, "관우", 1)
        val world = world(listOf(me, dest))
        val recorder = ChangeRecorder()
        // mailbox 7 = 상대 generalID(개인).
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 7, text = "안녕하신가"),
        )
        assertTrue((res as SendMessageResult).ok)
        assertEquals("private", res.msgType)
        val rows = recorder.createdMessages()
        assertEquals(2, rows.size)
        assertEquals(7, rows[0].mailbox) // receiver 메일함 = 상대 generalID.
        assertEquals(1, rows[1].mailbox) // sender 메일함 = 자기 generalID.
        assertEquals("private", rows[0].type)
        assertEquals(1, rows[0].srcId)
        assertEquals(7, rows[0].destId)
        // 수신자(7) newmsg=1.
        assertEquals(1, world.getGeneralById(7)!!.meta["newmsg"])
        assertTrue(recorder.dirtyGeneralIds().contains(7))
    }

    @Test
    fun `send private denied when dest general missing`() {
        val me = general(1, "유비", 1)
        val world = world(listOf(me))
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 99, text = "x"),
        )
        assertEquals("존재하지 않는 유저입니다.", (res as SendMessageResult).reason)
        assertTrue(recorder.createdMessages().isEmpty())
    }

    @Test
    fun `send private denied between two diplomats of different nations`() {
        val me = general(1, "유비", 1, meta = mapOf("permission" to 4))
        val destDiplo = general(2, "주유", 2, meta = mapOf("permission" to 4))
        val world = world(listOf(me, destDiplo))
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 2, text = "x"),
        )
        assertEquals("외교권자끼리는 메시지를 보낼 수 없습니다.", (res as SendMessageResult).reason)
        assertTrue(recorder.createdMessages().isEmpty())
    }

    @Test
    fun `send private denied when penalty NoSendPrivateMsg`() {
        val me = general(1, "유비", 1, meta = mapOf("penalty" to mapOf("no_send_private_msg" to true)))
        val dest = general(7, "관우", 1)
        val world = world(listOf(me, dest))
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 7, text = "x"),
        )
        assertEquals("개인 메세지를 보낼 수 없습니다.", (res as SendMessageResult).reason)
    }

    @Test
    fun `send private denied by throttle interval interpolates the delay`() {
        // lastMsg = now-1s, min interval = 5 → 1 < 5 → throttle deny (delay 보간).
        val nowSec = t0.epochSecond
        val me = general(
            1, "유비", 1,
            meta = mapOf("penalty" to mapOf("send_private_msg_delay" to 5), "lastMsg" to nowSec - 1),
        )
        val dest = general(7, "관우", 1)
        val world = world(listOf(me, dest))
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 7, text = "x"),
        )
        assertEquals("개인메세지는 5초당 1건만 보낼 수 있습니다!", (res as SendMessageResult).reason)
    }

    // ── SEND: 공통 deny 게이트 ──────────────────────────────────────────────────────────────────────

    @Test
    fun `send denied when acting general missing`() {
        val world = world(emptyList())
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 99, mailbox = 9999, text = "x"),
        )
        assertEquals("장수가 없습니다.", (res as SendMessageResult).reason)
    }

    @Test
    fun `send denied when blocked`() {
        val me = general(1, "유비", 1, meta = mapOf("blockLevel" to 1))
        val world = world(listOf(me))
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 9999, text = "x"),
        )
        assertEquals("차단되었습니다.", (res as SendMessageResult).reason)
    }

    @Test
    fun `send denied when access-limited`() {
        val me = general(1, "유비", 1)
        val world = world(
            listOf(me),
            accessLogs = listOf(
                GeneralAccessLog(
                    generalId = 1,
                    userId = 7,
                    lastRefresh = t0,
                    refresh = 2,
                    refreshTotal = 20,
                    refreshScore = 10,
                    refreshScoreTotal = 30,
                ),
            ),
            refreshLimit = 10,
        )
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 9999, text = "x"),
        )
        assertEquals("접속 제한입니다.", (res as SendMessageResult).reason)
        val accessLog = recorder.accessLogUpserts().single()
        assertEquals(3, accessLog.refresh)
        assertEquals(21, accessLog.refreshTotal)
        assertEquals(11, accessLog.refreshScore)
        assertEquals(31, accessLog.refreshScoreTotal)
        assertTrue(recorder.createdMessages().isEmpty())
    }

    @Test
    fun `send denied with unknown error for non-positive mailbox`() {
        val me = general(1, "유비", 1)
        val world = world(listOf(me))
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 0, text = "x"),
        )
        assertEquals("알 수 없는 에러입니다.", (res as SendMessageResult).reason)
    }

    // ── DELETE ──────────────────────────────────────────────────────────────────────────────────

    /** reader가 돌려줄 메시지 스냅샷을 만든다(production reader = ContactReader.findMessage 어댑트와 동일 형태). */
    private fun snapshot(
        id: Int = 100,
        type: String = "private",
        srcGeneralId: Int = 1,
        time: Instant = t0,
        deletable: Boolean? = null,
        receiverMessageId: Int? = null,
        hasAction: Boolean = false,
    ) = MessageSnapshot(
        id = id, hasAction = hasAction, type = type,
        srcGeneralId = srcGeneralId, srcNationId = 1, destGeneralId = 7, destNationId = 2,
        time = time, validUntil = Instant.parse("9999-12-31T23:59:59Z"),
        deletable = deletable, receiverMessageId = receiverMessageId,
        text = "원문", srcArray = mapOf("id" to 1), destArray = mapOf("id" to 7),
        option = receiverMessageId?.let { mapOf("receiverMessageID" to it) } ?: emptyMap(),
    )

    private fun deleteHandler(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        reader: (Int) -> MessageSnapshot?,
    ) = MessageHandler(world, recorder).apply { messageReader = reader }

    @Test
    fun `delete denied when message missing (no reader)`() {
        val world = world(listOf(general(1, "유비", 1)))
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleDelete(
            TurnDaemonCommand.DeleteMessage(generalId = 1, msgID = 100),
        )
        assertEquals("메시지가 없습니다", (res as DeleteMessageResult).reason)
    }

    @Test
    fun `delete denied when not the sender`() {
        val world = world(listOf(general(1, "유비", 1)))
        val recorder = ChangeRecorder()
        // 메시지의 src=99 ≠ 요청자 1.
        val res = deleteHandler(world, recorder) { snapshot(srcGeneralId = 99) }.handleDelete(
            TurnDaemonCommand.DeleteMessage(generalId = 1, msgID = 100),
        )
        assertEquals("본인의 메시지만 삭제할 수 있습니다.", (res as DeleteMessageResult).reason)
        assertTrue(recorder.messageInvalidates().isEmpty())
    }

    @Test
    fun `delete denied for a diplomatic system message`() {
        val world = world(listOf(general(1, "유비", 1)))
        val recorder = ChangeRecorder()
        val res = deleteHandler(world, recorder) {
            snapshot(type = "diplomacy", hasAction = true)
        }.handleDelete(TurnDaemonCommand.DeleteMessage(generalId = 1, msgID = 100))
        assertEquals("시스템 외교 메시지는 삭제할 수 없습니다.", (res as DeleteMessageResult).reason)
    }

    @Test
    fun `delete denied outside the 5-minute window`() {
        val world = world(listOf(general(1, "유비", 1)))
        val recorder = ChangeRecorder()
        // 메시지 시각 = now - 6분 → 5분 윈도우 밖.
        val res = deleteHandler(world, recorder) {
            snapshot(time = t0.minusSeconds(6 * 60))
        }.handleDelete(TurnDaemonCommand.DeleteMessage(generalId = 1, msgID = 100))
        assertEquals("5분 이내의 메시지만 삭제할 수 있습니다.", (res as DeleteMessageResult).reason)
    }

    @Test
    fun `delete denied when message not deletable`() {
        val world = world(listOf(general(1, "유비", 1)))
        val recorder = ChangeRecorder()
        val res = deleteHandler(world, recorder) { snapshot(deletable = false) }.handleDelete(
            TurnDaemonCommand.DeleteMessage(generalId = 1, msgID = 100),
        )
        assertEquals("삭제할 수 없는 메시지입니다.", (res as DeleteMessageResult).reason)
    }

    @Test
    fun `delete invalidates the sender copy and writes 삭제된 메시지입니다 text`() {
        val world = world(listOf(general(1, "유비", 1)))
        val recorder = ChangeRecorder()
        val res = deleteHandler(world, recorder) { snapshot() }.handleDelete(
            TurnDaemonCommand.DeleteMessage(generalId = 1, msgID = 100),
        )
        assertTrue((res as DeleteMessageResult).ok)
        // sender 사본 무효화 UPDATE 1건 + req_del_msg 마커 INSERT 1건.
        val inv = recorder.messageInvalidates()
        assertEquals(1, inv.size)
        assertEquals(100, inv.single().id)
        val invBody = bodyOf(inv.single().bodyJson)
        assertEquals("삭제된 메시지입니다.", invBody["text"]) // 런타임 본문(Q-A3 — 골든 아님).
        @Suppress("UNCHECKED_CAST")
        val opt = invBody["option"] as Map<String, Any?>
        assertEquals(true, opt["invalid"])
        // 마커 INSERT.
        assertEquals(1, recorder.createdMessages().size)
        assertEquals("req_del_msg", bodyOf(recorder.createdMessages().single().bodyJson)["text"])
    }

    @Test
    fun `delete reciprocally invalidates the receiver copy`() {
        val world = world(listOf(general(1, "유비", 1)))
        val recorder = ChangeRecorder()
        // sender 사본(id=100)이 receiverMessageID=200을 가리킨다 → 수신측(200)도 무효화.
        val res = deleteHandler(world, recorder) { id ->
            when (id) {
                100 -> snapshot(id = 100, receiverMessageId = 200)
                200 -> snapshot(id = 200, srcGeneralId = 1)
                else -> null
            }
        }.handleDelete(TurnDaemonCommand.DeleteMessage(generalId = 1, msgID = 100))

        assertTrue((res as DeleteMessageResult).ok)
        // 무효화 UPDATE 2건: sender(100) + receiver(200).
        val invIds = recorder.messageInvalidates().map { it.id }.toSet()
        assertEquals(setOf(100, 200), invIds)
    }
}
