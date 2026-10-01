package opensamguk.engine.run

import opensamguk.common.wire.*
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.infra.read.BoardPostRepository
import opensamguk.logic.council.CouncilRequest
import opensamguk.logic.council.CouncilRequestCodec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.Instant

class CouncilDispatchTest {
    private val at = Instant.parse("0200-01-01T00:00:00Z")
    private val actor = TurnGeneral(id = 10, name = "본인", userId = "7", nationId = 1, cityId = 5,
        troopId = 0, stats = GeneralStats(70, 70, 70), experience = 0, dedication = 0,
        officerLevel = 12, npcState = 0, turnTime = at)
    private val world = InMemoryTurnWorld(WorldSnapshot(worldId = WorldId(1),
        state = TurnWorldState(1, 200, 1, 3600, at, config = mapOf("ruleProfile" to "HWIHA")),
        generals = listOf(actor), nations = listOf(Nation(1, "본국", "#111111"))))
    private val recorder = ChangeRecorder()
    private val posts = mock(BoardPostRepository::class.java)
    private val dispatcher = TurnDaemonCommandDispatcher(world, recorder, posts)
    private val command = TurnDaemonCommand.CouncilInput("request-1", 10, 7, 1,
        CouncilRequestCodec.POST_ARTICLE, CouncilRequestCodec.encode(CouncilRequest.PostArticle(
            "MEETING", "GENERAL", "제목", "본문", null)), null)

    @Test fun `wire 왕복 뒤 같은 요청 영수증으로 별도 사회 채널이 dispatch된다`() {
        val envelope = TurnDaemonCommandEnvelope(command.requestId, at.toString(), command)
        val payload = encodeCommandPayload(envelope)
        val decoded = WireJson.decodeFromString(TurnDaemonCommandEnvelope.serializer(), payload)
        assertEquals(envelope, decoded)
        val (receipt, response) = dispatcher.dispatchEnvelopes(listOf(decoded)).single()
        assertEquals(command.requestId, receipt)
        val result = response as CommandLifecycleResult
        assertTrue(result.ok); assertEquals("CouncilInput:POST_ARTICLE", result.actionCode)
        assertEquals("IMMEDIATE", result.commandKind); assertNull(result.inputResolved)
        assertEquals(1, recorder.boardPostInserts().size)
    }

    @Test fun `envelope와 내부 requestId가 다르면 접수나 본문 조회 없이 거절한다`() {
        val (receipt, response) = dispatcher.dispatchEnvelopes(listOf(
            TurnDaemonCommandEnvelope("other-request", at.toString(), command))).single()
        assertEquals("other-request", receipt)
        assertEquals("REQUEST_ID_MISMATCH", (response as CommandLifecycleResult).code)
        assertFalse(response.ok)
        assertTrue(recorder.boardPostInserts().isEmpty()); assertTrue(recorder.kvDirty().isEmpty())
        verifyNoInteractions(posts)
    }

    @Test fun `권한 어댑터가 미연결인 기본값은 직함으로 SECRET 쓰기를 열지 않는다`() {
        val secret = command.copy(argJson = CouncilRequestCodec.encode(
            CouncilRequest.PostArticle("SECRET", "GENERAL", "제목", "본문", null)))
        val response = dispatcher.dispatchEnvelopes(listOf(
            TurnDaemonCommandEnvelope(secret.requestId, at.toString(), secret))).single().second as CommandLifecycleResult
        assertFalse(response.ok); assertEquals("SECRET_ACCESS_REQUIRED", response.code)
        assertTrue(recorder.boardPostInserts().isEmpty())
        verifyNoInteractions(posts)
    }
}
