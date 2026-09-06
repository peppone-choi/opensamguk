package opensamguk.logic.actions.intake

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 게시판(회의실/기밀실) 입력 검증 + 권한 게이트에 대한 순수-로직 패러티 테스트. 오라클 =
 * PHP `j_board_article_add.php` / `j_board_comment_add.php`의 반환 문자열 그대로.
 */
class BoardActionsTest {

    // ── addArticle ──────────────────────────────────────────────────────────────────────────────
    @Test
    fun `addArticle inserts when input valid and permission allows`() {
        assertEquals(
            BoardActions.ArticleOutcome.Insert(isSecret = false, title = "제목", text = "내용"),
            BoardActions.addArticle(isSecret = false, rawTitle = "제목", rawText = "내용", permission = 0),
        )
        // 기밀 게시판은 permission >= 2가 필요하다.
        assertEquals(
            BoardActions.ArticleOutcome.Insert(isSecret = true, title = "기밀", text = "내용"),
            BoardActions.addArticle(isSecret = true, rawTitle = "기밀", rawText = "내용", permission = 2),
        )
    }

    @Test
    fun `addArticle denies null title or text (절대 입력 누락)`() {
        assertEquals(
            BoardActions.ArticleOutcome.Denied("올바르지 않은 입력입니다."),
            BoardActions.addArticle(isSecret = false, rawTitle = null, rawText = "내용", permission = 4),
        )
        assertEquals(
            BoardActions.ArticleOutcome.Denied("올바르지 않은 입력입니다."),
            BoardActions.addArticle(isSecret = false, rawTitle = "제목", rawText = null, permission = 4),
        )
    }

    @Test
    fun `addArticle denies when title and text both blank after trim`() {
        assertEquals(
            BoardActions.ArticleOutcome.Denied("제목과 내용이 둘다 비어있습니다."),
            BoardActions.addArticle(isSecret = false, rawTitle = "  ", rawText = "\t", permission = 4),
        )
        // 한쪽이 비어있지 않으면 둘다-비었음 가드를 통과한다.
        assertEquals(
            BoardActions.ArticleOutcome.Insert(isSecret = false, title = "제목", text = ""),
            BoardActions.addArticle(isSecret = false, rawTitle = "제목", rawText = "  ", permission = 0),
        )
    }

    @Test
    fun `addArticle denies nationless then secret-without-chief`() {
        assertEquals(
            BoardActions.ArticleOutcome.Denied("국가에 소속되어있지 않습니다."),
            BoardActions.addArticle(isSecret = false, rawTitle = "제목", rawText = "내용", permission = -1),
        )
        assertEquals(
            BoardActions.ArticleOutcome.Denied("권한이 부족합니다. 수뇌부가 아닙니다."),
            BoardActions.addArticle(isSecret = true, rawTitle = "기밀", rawText = "내용", permission = 1),
        )
    }

    // ── comment ─────────────────────────────────────────────────────────────────────────────────
    @Test
    fun `validateCommentText trims and distinguishes null from blank`() {
        assertEquals(BoardActions.CommentTextOutcome.Ok("댓글"), BoardActions.validateCommentText("  댓글  "))
        assertEquals(
            BoardActions.CommentTextOutcome.Denied("올바르지 않은 입력입니다."),
            BoardActions.validateCommentText(null),
        )
        assertEquals(
            BoardActions.CommentTextOutcome.Denied("내용이 비어있습니다."),
            BoardActions.validateCommentText("   "),
        )
    }

    @Test
    fun `commentPermissionDeny mirrors the article gates`() {
        assertEquals("국가에 소속되어있지 않습니다.", BoardActions.commentPermissionDeny(isSecret = false, permission = -1))
        assertEquals("권한이 부족합니다. 수뇌부가 아닙니다.", BoardActions.commentPermissionDeny(isSecret = true, permission = 1))
        assertNull(BoardActions.commentPermissionDeny(isSecret = true, permission = 2))
        assertNull(BoardActions.commentPermissionDeny(isSecret = false, permission = 0))
    }

    // ── ADR-LITE-049 14 — 글 종류(kind)·표결 연결·열람 게이트 ─────────────────────
    @Test
    fun `addArticle carries kind and voteId for vote posts and defaults to general`() {
        assertEquals(
            BoardActions.ArticleOutcome.Insert(isSecret = false, title = "표결", text = "본문", kind = "vote", voteId = 3),
            BoardActions.addArticle(isSecret = false, rawTitle = "표결", rawText = "본문", permission = 0, kind = "vote", voteId = 3),
        )
        assertEquals("general", (BoardActions.addArticle(false, "제목", "내용", 0) as BoardActions.ArticleOutcome.Insert).kind)
        // 표결이 아닌 글의 voteId 는 버린다(연결 없음).
        assertNull((BoardActions.addArticle(false, "제목", "내용", 0, kind = "operation", voteId = 9) as BoardActions.ArticleOutcome.Insert).voteId)
    }

    @Test
    fun `addArticle denies an unknown kind and a vote post without voteId`() {
        assertEquals(
            BoardActions.ArticleOutcome.Denied("올바르지 않은 입력입니다."),
            BoardActions.addArticle(false, "제목", "내용", 4, kind = "secret"),
        )
        assertEquals(
            BoardActions.ArticleOutcome.Denied("올바르지 않은 입력입니다."),
            BoardActions.addArticle(false, "표결", "내용", 4, kind = "vote", voteId = null),
        )
    }

    @Test
    fun `notice posts need 수뇌부 permission`() {
        assertEquals(
            BoardActions.ArticleOutcome.Denied("권한이 부족합니다. 수뇌부가 아닙니다."),
            BoardActions.addArticle(false, "공지", "내용", 1, kind = "notice"),
        )
        assertEquals(
            BoardActions.ArticleOutcome.Insert(false, "공지", "내용", kind = "notice"),
            BoardActions.addArticle(false, "공지", "내용", 2, kind = "notice"),
        )
    }

    @Test
    fun `readPermissionDeny mirrors the comment gate`() {
        assertEquals("국가에 소속되어있지 않습니다.", BoardActions.readPermissionDeny(isSecret = true, permission = -1))
        assertEquals("권한이 부족합니다. 수뇌부가 아닙니다.", BoardActions.readPermissionDeny(isSecret = true, permission = 1))
        assertNull(BoardActions.readPermissionDeny(isSecret = true, permission = 2))
        assertNull(BoardActions.readPermissionDeny(isSecret = false, permission = 0))
    }
}
