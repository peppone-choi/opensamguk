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
import java.time.OffsetDateTime
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
            worldId = opensamguk.common.world.WorldId((TurnWorldState(
                id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0,
                meta = mapOf("refreshLimit" to refreshLimit),
            )).id),
        ),
    )

    private fun handler(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        MessageHandler(world, recorder)

    private fun MessageHandler.handleSend(c: TurnDaemonCommand.SendMessage) = handleSend(c, t0)

    private fun MessageHandler.handleDelete(c: TurnDaemonCommand.DeleteMessage) = handleDelete(c, t0)

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
        val body = bodyOf(row.bodyJson)
        assertEquals(body["src"], body["dest"])
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

    @Test
    fun `send to a foreign nation mailbox is denied without diplomacy permission`() {
        val me = general(1, "유비", 1)
        val world = world(listOf(me))
        val recorder = ChangeRecorder()

        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 9002, text = "권한 없는 외교"),
        )

        assertEquals("외교 권한이 없습니다.", (res as SendMessageResult).reason)
        assertTrue(recorder.createdMessages().isEmpty())
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

    @Test
    fun `send diplomacy permits an unpenalized lord without fabricated permission meta`() {
        val lord = general(1, "유비", 1, officerLevel = 12)
        val caocao = general(2, "조조", 2, officerLevel = 12)
        val world = world(listOf(lord, caocao))
        val recorder = ChangeRecorder()

        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 9002, text = "군주 외교"),
        )

        assertTrue((res as SendMessageResult).ok)
        assertEquals("diplomacy", res.msgType)
        assertEquals(9002, recorder.createdMessages().first().mailbox)
    }

    @Test
    fun `send diplomacy denies a lord whose secret permission is clamped by penalty`() {
        val penalizedLord = general(
            1,
            "유비",
            1,
            officerLevel = 12,
            meta = mapOf("penalty" to mapOf("noTopSecret" to true)),
        )
        val world = world(listOf(penalizedLord))
        val recorder = ChangeRecorder()

        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 9002, text = "징계 중 외교"),
        )

        assertEquals("외교 권한이 없습니다.", (res as SendMessageResult).reason)
        assertTrue(recorder.createdMessages().isEmpty())
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
        assertEquals(7, res.recipientId)
        assertEquals("관우", res.recipientName)
        val rows = recorder.createdMessages()
        assertEquals(2, rows.size)
        assertEquals(7, rows[0].mailbox) // receiver 메일함 = 상대 generalID.
        assertEquals(1, rows[1].mailbox) // sender 메일함 = 자기 generalID.
        assertEquals("private", rows[0].type)
        assertEquals(1, rows[0].srcId)
        assertEquals(7, rows[0].destId)
        @Suppress("UNCHECKED_CAST")
        val storedDest = bodyOf(rows[0].bodyJson)["dest"] as Map<String, Any?>
        assertEquals(7, (storedDest["id"] as Number).toInt())
        assertEquals("관우", storedDest["name"])
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
    fun `send private denied when recipient is the sender`() {
        val me = general(1, "유비", 1)
        val world = world(listOf(me))
        val recorder = ChangeRecorder()

        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 1, text = "자기 자신에게"),
        )

        assertEquals("자기 자신에게는 개인 서신을 보낼 수 없습니다.", (res as SendMessageResult).reason)
        assertTrue(recorder.createdMessages().isEmpty())
        assertTrue(recorder.generalPatches().isEmpty())
        assertNull(world.getGeneralById(1)!!.meta["lastMsg"])
    }

    @Test
    fun `send private denied when recipient is not a playable contact`() {
        val me = general(1, "유비", 1)
        val hidden = general(7, "숨겨진장수", 1, npcState = 2)
        val world = world(listOf(me, hidden))
        val recorder = ChangeRecorder()

        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 7, text = "숨겨진 대상"),
        )

        assertEquals("수신할 수 없는 장수입니다.", (res as SendMessageResult).reason)
        assertTrue(recorder.createdMessages().isEmpty())
        assertTrue(recorder.generalPatches().isEmpty())
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

    @Test
    fun `private send rejects an invalid destination without persisting lastMsg`() {
        val sentAt = t0.plusSeconds(60)
        val me = general(
            1,
            "유비",
            1,
            meta = mapOf(
                "penalty" to mapOf("send_private_msg_delay" to 2),
                "lastMsg" to sentAt.minusSeconds(3).epochSecond,
            ),
        )
        val world = world(listOf(me))
        val recorder = ChangeRecorder()

        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 99, text = "없는 장수에게"),
            sentAt,
        )

        assertEquals("존재하지 않는 유저입니다.", (res as SendMessageResult).reason)
        assertEquals(sentAt.minusSeconds(3).epochSecond, world.getGeneralById(1)!!.meta["lastMsg"])
        assertTrue(recorder.generalPatches().isEmpty())
        assertTrue(recorder.createdMessages().isEmpty())
    }

    @Test
    fun `private send throttle denial does not replace lastMsg`() {
        val sentAt = t0.plusSeconds(60)
        val previousLastMsg = sentAt.minusSeconds(1).epochSecond
        val me = general(
            1,
            "유비",
            1,
            meta = mapOf(
                "penalty" to mapOf("send_private_msg_delay" to 5),
                "lastMsg" to previousLastMsg,
            ),
        )
        val dest = general(7, "관우", 1)
        val world = world(listOf(me, dest))
        val recorder = ChangeRecorder()

        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 7, text = "너무 빠른 서신"),
            sentAt,
        )

        assertEquals("개인메세지는 5초당 1건만 보낼 수 있습니다!", (res as SendMessageResult).reason)
        assertEquals(previousLastMsg, world.getGeneralById(1)!!.meta["lastMsg"])
        assertTrue(recorder.generalPatches().isEmpty())
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
    fun `send denied with a clear reason for a missing mailbox`() {
        val me = general(1, "유비", 1)
        val world = world(listOf(me))
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 0, text = "x"),
        )
        assertEquals("발송 대상이 없습니다.", (res as SendMessageResult).reason)
        assertTrue(recorder.createdMessages().isEmpty())
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
        id = id, mailbox = 7, hasAction = hasAction, type = type,
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
    fun `delete age gate uses envelope time`() {
        val sentAt = t0.plusSeconds(6 * 60)
        val world = world(listOf(general(1, "유비", 1)))
        val recorder = ChangeRecorder()
        val res = deleteHandler(world, recorder) { snapshot(time = t0) }.handleDelete(
            TurnDaemonCommand.DeleteMessage(generalId = 1, msgID = 100),
            sentAt,
        )

        assertEquals("5분 이내의 메시지만 삭제할 수 있습니다.", (res as DeleteMessageResult).reason)
        assertTrue(recorder.messageInvalidates().isEmpty())
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
        val inv = recorder.messageInvalidates()
        assertEquals(1, inv.size)
        assertEquals(100, inv.single().id)
        val invBody = bodyOf(inv.single().bodyJson)
        assertEquals("삭제된 메시지입니다.", invBody["text"]) // 런타임 본문(Q-A3 — 골든 아님).
        @Suppress("UNCHECKED_CAST")
        val opt = invBody["option"] as Map<String, Any?>
        assertEquals(true, opt["invalid"])
        val markers = recorder.createdMessages()
        assertEquals(2, markers.size)
        assertEquals("req_del_msg", bodyOf(markers[0].bodyJson)["text"])
        assertEquals("req_del_msg", bodyOf(markers[1].bodyJson)["text"])
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

    @Test
    fun `delete public marker preserves the stored source and destination participants`() {
        val sentAt = t0.plusSeconds(90)
        val world = world(listOf(general(1, "유비", 1)))
        val recorder = ChangeRecorder()
        val publicMessage = snapshot(type = "public").copy(
            destGeneralId = 1,
            destNationId = 1,
            destArray = mapOf("id" to 1),
        )

        val res = deleteHandler(world, recorder) { publicMessage }.handleDelete(
            TurnDaemonCommand.DeleteMessage(generalId = 1, msgID = 100),
            sentAt,
        )

        assertTrue((res as DeleteMessageResult).ok)
        val marker = recorder.createdMessages().single()
        assertEquals(MessageHandler.MAILBOX_PUBLIC, marker.mailbox)
        assertEquals("public", marker.type)
        assertEquals(1, marker.srcId)
        assertEquals(MessageHandler.MAILBOX_PUBLIC, marker.destId)
        assertEquals(MessageHandler.formatPhpDate(sentAt), marker.time)
        val body = bodyOf(marker.bodyJson)
        assertEquals(body["src"], body["dest"])
        assertEquals("req_del_msg", body["text"])
    }

    @Test
    fun `delete writes private req_del_msg receiver then sender copy with original participants`() {
        val sentAt = t0.plusSeconds(90)
        val world = world(listOf(general(1, "유비", 1)))
        val recorder = ChangeRecorder()
        val res = deleteHandler(world, recorder) { id ->
            when (id) {
                100 -> snapshot(id = 100, receiverMessageId = 200)
                200 -> snapshot(id = 200, srcGeneralId = 1)
                else -> null
            }
        }.handleDelete(TurnDaemonCommand.DeleteMessage(generalId = 1, msgID = 100), sentAt)

        assertTrue((res as DeleteMessageResult).ok)
        assertEquals(listOf(200, 100), recorder.messageInvalidates().map { it.id })

        val rows = recorder.createdMessages()
        assertEquals(2, rows.size)
        val receiver = rows[0]
        val sender = rows[1]
        assertEquals(7, receiver.mailbox)
        assertEquals(1, sender.mailbox)
        assertEquals("private", receiver.type)
        assertEquals("private", sender.type)
        assertEquals(1, receiver.srcId)
        assertEquals(7, receiver.destId)
        assertEquals(1, sender.srcId)
        assertEquals(7, sender.destId)
        assertEquals(MessageHandler.formatPhpDate(sentAt), receiver.time)
        assertEquals(MessageHandler.formatPhpDate(sentAt.plusSeconds(60)), receiver.validUntil)

        val receiverBody = bodyOf(receiver.bodyJson)
        val senderBody = bodyOf(sender.bodyJson)
        assertEquals(mapOf("id" to 1), receiverBody["src"])
        assertEquals(mapOf("id" to 7), receiverBody["dest"])
        assertEquals("req_del_msg", receiverBody["text"])
        @Suppress("UNCHECKED_CAST")
        val receiverOption = receiverBody["option"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val senderOption = senderBody["option"] as Map<String, Any?>
        assertEquals(true, receiverOption["hide"])
        assertEquals(true, receiverOption["silence"])
        val overwrite = receiverOption["overwrite"] as List<*>
        assertEquals(100, (overwrite[0] as Number).toInt())
        assertEquals(200, ((overwrite[1] as List<*>).single() as Number).toInt())
        assertNull(receiverOption["receiverMessageID"])
        assertEquals(receiver.id, (senderOption["receiverMessageID"] as Number).toInt())
        assertEquals(receiverOption["overwrite"], senderOption["overwrite"])
    }

    /**
     * 회귀 — 저장 시각 문자열은 오프셋을 반드시 포함한다.
     *
     * flush는 `CAST(:time AS timestamptz)`로 바인딩한다(`JdbcFlushExecutor.messageCreateMany`).
     * 오프셋이 없는 `yyyy-MM-dd HH:mm:ss` 문자열을 넘기면 PostgreSQL이 세션 TimeZone으로 해석하므로,
     * UTC 벽시계가 KST(+09)로 읽혀 저장 시각이 9시간 과거가 된다. 그 상태에서는 방금 보낸 서신도
     * `deleteMessage`의 "5분 이내" 게이트에 걸려 사용자가 자기 서신을 영영 삭제할 수 없다.
     *
     * 로컬 도커 스택 실측(2026-08-18): `now() - time` = 09:01:06 (1분 전에 만든 서신).
     * 왕복 비교만 하는 기존 테스트·IT는 세션 TZ가 일정하면 통과하므로 이 결함을 잡지 못했다.
     */
    @Test
    fun `formatPhpDate가 오프셋을 포함해 절대시각을 보존한다`() {
        val sentAt = Instant.parse("2026-08-18T03:59:02Z")
        val formatted = MessageHandler.formatPhpDate(sentAt)

        // 오프셋이 문자열에 있어야 세션 TimeZone 과 무관하게 해석된다.
        assertEquals("2026-08-18 03:59:02Z", formatted)

        // 되읽으면 원래 순간과 정확히 같다 — 시간대 없는 포맷이면 여기서 파싱 자체가 실패한다.
        val parsed = OffsetDateTime.parse(formatted.replace(" ", "T")).toInstant()
        assertEquals(sentAt, parsed)
    }
}
