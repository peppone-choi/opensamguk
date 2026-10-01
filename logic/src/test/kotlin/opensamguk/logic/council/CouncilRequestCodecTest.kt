package opensamguk.logic.council

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CouncilRequestCodecTest {
    @Test
    fun `게시 댓글 열람 지정 회수의 서로 다른 payload를 정규화한다`() {
        val rows = listOf(
            CouncilRequestCodec.POST_ARTICLE to CouncilRequest.PostArticle("SECRET", "GENERAL", "제목", "본문", null),
            CouncilRequestCodec.POST_COMMENT to CouncilRequest.PostComment(42, "댓글"),
            CouncilRequestCodec.MARK_READ to CouncilRequest.MarkRead(42),
            CouncilRequestCodec.GRANT_ACCESS to CouncilRequest.GrantAccess(102, null),
            CouncilRequestCodec.REVOKE_ACCESS to CouncilRequest.RevokeAccess(102, "receipt-1"))
        rows.forEach { (action, row) -> assertEquals(row, CouncilRequestCodec.parse(action, CouncilRequestCodec.encode(row))) }
    }

    @Test
    fun `client의 주체 세력 권한과 표결을 접수하지 않는다`() {
        val valid = """{"room":"MEETING","kind":"GENERAL","title":"글","contentHtml":"내용"}"""
        listOf("generalId", "ownerUserId", "nationId", "permission", "authorityRevision").forEach { field ->
            assertNull(CouncilRequestCodec.parse(CouncilRequestCodec.POST_ARTICLE, valid.dropLast(1) + ",\"$field\":101}"))
        }
        assertNull(CouncilRequestCodec.parse(CouncilRequestCodec.POST_ARTICLE, valid.replace("GENERAL", "VOTE")))
    }

    @Test
    fun `거짓 숫자 경계 밖 길이 빈 댓글과 종류 밖 작전 연결을 거절한다`() {
        assertNull(CouncilRequestCodec.parse(CouncilRequestCodec.MARK_READ, """{"articleId":"42"}"""))
        assertNull(CouncilRequestCodec.parse(CouncilRequestCodec.MARK_READ, """{"articleId":0}"""))
        assertNull(CouncilRequestCodec.parse(CouncilRequestCodec.POST_COMMENT, """{"articleId":42,"text":" "}"""))
        assertNull(CouncilRequestCodec.parse(CouncilRequestCodec.POST_COMMENT,
            CouncilRequestCodec.encode(CouncilRequest.PostComment(42, "가".repeat(251)))))
        assertNull(CouncilRequestCodec.parse(CouncilRequestCodec.POST_ARTICLE,
            CouncilRequestCodec.encode(CouncilRequest.PostArticle("MEETING", "GENERAL", "글", "본문", 10))))
    }
}
