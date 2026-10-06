package opensamguk.gameapi.council

import opensamguk.common.wire.CouncilInput
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.read.*
import opensamguk.logic.council.CouncilRequest
import opensamguk.logic.council.CouncilRequestCodec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class CouncilAdmissionTest {
    private val reader = mock(CouncilReader::class.java)
    private val posts = mock(BoardPostReadRepository::class.java)
    private val operations = mock(OperationReadRepository::class.java)
    private val actor = GeneralReadEntity(id = 10, worldId = 1, nationId = 1, userId = "7")
    private val peer = GeneralReadEntity(id = 11, worldId = 1, nationId = 1)
    private val world = WorldStateReadEntity(id = 1, config = mapOf("ruleProfile" to "HWIHA"))
    private val admission = CouncilAdmission(reader, posts, operations)
    private fun session(proof: CouncilAuthority = CouncilAuthority.unavailable()) {
        `when`(reader.session(7L)).thenReturn(CouncilSession(actor, world, listOf(actor, peer), proof))
    }
    init { session() }
    private fun article(room: String = "MEETING", kind: String = "GENERAL", operation: Int? = null) =
        CouncilRequestCodec.encode(CouncilRequest.PostArticle(room, kind, "제목", "<p>본문</p>", operation))
    private fun rejection(action: String, raw: String): CouncilReadFailure = assertThrows(CouncilReadFailure::class.java) {
        admission.command(7L, action, raw)
    }

    @Test fun `principal을 소유 주체로 바인딩하고 HTML과 페이로드를 정규화한다`() {
        val command = admission.command(7L, CouncilRequestCodec.POST_ARTICLE,
            """{"room":"MEETING","kind":"GENERAL","title":"  제목  ","contentHtml":"<p>본문</p><script>bad()</script>"}""")
        assertEquals(10, command.generalId); assertEquals(1, command.nationId); assertEquals(7, command.ownerUserId)
        assertEquals("", command.requestId)
        assertNull(command.authorityRevision)
        val request = CouncilRequestCodec.parse(command.action, command.argJson) as CouncilRequest.PostArticle
        assertEquals("제목", request.title)
        assertFalse(request.contentHtml.contains("script")); assertFalse(request.contentHtml.contains("bad()"))
        verifyNoInteractions(posts, operations)
    }

    @Test fun `클라이언트 actor owner 소속 또는 권한을 접수 페이로드로 받지 않는다`() {
        listOf("generalId", "ownerUserId", "nationId", "permission", "authorityRevision").forEach { key ->
            val raw = article().dropLast(1) + ",\"$key\":12}"
            assertEquals("INVALID_REQUEST", rejection(CouncilRequestCodec.POST_ARTICLE, raw).code)
        }
        verifyNoInteractions(posts, operations)
    }

    @Test fun `무소속은 쓰기403이고 비공개 부모나 작전 정보를 읽지 않는다`() {
        actor.nationId = 0; session()
        assertEquals(403, rejection(CouncilRequestCodec.POST_ARTICLE, article()).status)
        assertEquals("NO_AFFILIATION", rejection(CouncilRequestCodec.MARK_READ, """{"articleId":40}""").code)
        verifyNoInteractions(posts, operations)
    }

    @Test fun `공식 SECRET 근거가 없으면 높은 직함이나 부재 명부를 허용으로 바꾸지 않는다`() {
        actor.officerLevel = 12; actor.meta = mapOf("lord" to true)
        assertEquals("STATE_UNAVAILABLE", rejection(CouncilRequestCodec.POST_ARTICLE, article("SECRET")).code)
        session(CouncilAuthority(emptySet(), emptySet(), emptySet(), emptyMap(), true))
        assertEquals("SECRET_ACCESS_REQUIRED", rejection(CouncilRequestCodec.POST_ARTICLE, article("SECRET")).code)
        assertEquals("NOTICE_ACCESS_REQUIRED", rejection(CouncilRequestCodec.POST_ARTICLE, article(kind = "NOTICE")).code)
        verifyNoInteractions(posts, operations)
    }

    @Test fun `댓글은 허용 방 SQL을 먼저 적용하고 타국이나 secret 대역 행도 거절한다`() {
        val raw = """{"articleId":40,"text":"댓글"}"""
        assertEquals("FORBIDDEN", rejection(CouncilRequestCodec.POST_COMMENT, raw).code)
        verify(posts).councilArticle(1, 40, false)
        `when`(posts.councilArticle(1, 40, false)).thenReturn(BoardPostReadEntity(id = 40, worldId = 1,
            nationId = 2, isSecret = false))
        assertEquals("FORBIDDEN", rejection(CouncilRequestCodec.POST_COMMENT, raw).code)
        `when`(posts.councilArticle(1, 40, false)).thenReturn(BoardPostReadEntity(id = 40, worldId = 1,
            nationId = 1, isSecret = true))
        assertEquals("FORBIDDEN", rejection(CouncilRequestCodec.POST_COMMENT, raw).code)
        verify(posts, never()).findById(anyInt())
    }

    @Test fun `타국 연결 작전은 다른 세력 조회 없이 거절한다`() {
        `when`(operations.operationsOf(1)).thenReturn(emptyList())
        assertEquals("FORBIDDEN", rejection(CouncilRequestCodec.POST_ARTICLE, article(kind = "OPERATION", operation = 999)).code)
        verify(operations).operationsOf(1)
        verify(operations, never()).findById(anyInt())
    }

    @Test fun `군주 지정은 현재 영수증을 담고 stale 명부나 타국 대상은 거절한다`() {
        session(CouncilAuthority(setOf(10), setOf(10), setOf(10), mapOf(10 to "군주"), false,
            10, "ruler-1", "designation-1"))
        val action = CouncilRequestCodec.GRANT_ACCESS
        assertEquals(409, rejection(action, """{"targetGeneralId":11,"expectedRevision":"old"}""").status)
        assertEquals("FORBIDDEN", rejection(action, """{"targetGeneralId":20,"expectedRevision":"designation-1"}""").code)
        val command = admission.command(7L, action, """{"targetGeneralId":11,"expectedRevision":"designation-1"}""")
        assertEquals("ruler-1", command.authorityRevision)
        assertEquals(10, command.generalId)
        verifyNoInteractions(posts, operations)
    }

    @Test fun `내부 publish 호출도 이전 소속이나 타인 actor를 신원으로 사용할 수 없다`() {
        val wrong = CouncilInput("old", 20, 7, 2, CouncilRequestCodec.POST_ARTICLE, article(), null)
        assertEquals("FORBIDDEN", assertThrows(CouncilReadFailure::class.java) { admission.rebind(wrong, 7) }.code)
        verifyNoInteractions(posts, operations)
    }
}
