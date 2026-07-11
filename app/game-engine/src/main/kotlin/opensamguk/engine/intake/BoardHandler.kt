package opensamguk.engine.intake

import opensamguk.common.wire.BoardActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.infra.read.BoardPostRepository
import opensamguk.logic.actions.intake.BoardActions
import opensamguk.logic.actions.intake.SecretPermission
import java.time.Instant

/**
 * 회의실/기밀실 (board) intake 핸들러 — `j_board_article_add.php` /
 * `j_board_comment_add.php` `launch()` 본문의 faithful 포팅 (F4 Wave C2 slice C). per-run (world + recorder +
 * board_post read repo), [TroopHandler]를 미러링.
 *
 * 순수 검증 + 권한 게이트는 [BoardActions]에 있다. 이 핸들러는 PHP가 `DB::db()`로 수행하는
 * 부수 효과를 담당한다: 댓글의 게시물 존재 여부 `SELECT board WHERE no AND nation_no`
 * ([boardPostRepository]를 통해, 게시물의 `is_secret`을 산출) 와 board_post/board_comment
 * INSERT로, [ChangeRecorder]의 social-content 채널에 기록된다 (betting/auction을 미러링 —
 * board 게시물은 InMemoryTurnWorld 게임 상태가 아니므로 world create/update 경로를 절대 건드리지 않는다).
 *
 * 권한: 기본값을 사용한 `SecretPermission.check(me)` (checkSecretLimit = false) — 두 board 게이트 모두에 대해
 * PHP의 `checkSecretPermission($me)`와 게이트 동등 (see [BoardActions] 클래스 doc). RNG-free.
 */
class BoardHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val boardPostRepository: BoardPostRepository,
    private val nowProvider: () -> Instant = Instant::now,
) {
    // ── j_board_article_add.php ────────────────────────────────────────────────────────────────
    fun handleArticle(c: TurnDaemonCommand.BoardArticle): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return BoardActionResult("boardArticle", ok = false, generalId = c.generalId, reason = "장수가 존재하지 않습니다.")
        if (AccessLogThrottle(world, recorder, nowProvider).increaseAndBlocked(c.generalId)) {
            return BoardActionResult("boardArticle", ok = false, generalId = c.generalId, reason = "접속 제한입니다.")
        }

        val permission = SecretPermission.check(PerTurnOverlay.toLogicGeneral(me))
        return when (val out = BoardActions.addArticle(c.isSecret, c.title, c.text, permission)) {
            is BoardActions.ArticleOutcome.Denied ->
                BoardActionResult("boardArticle", ok = false, generalId = c.generalId, reason = out.reason)
            is BoardActions.ArticleOutcome.Insert -> {
                recorder.recordBoardPostInsert(
                    linkedMapOf(
                        "nation_id" to me.nationId,
                        "is_secret" to out.isSecret,
                        "author_general_id" to me.id,
                        "author_name" to me.name,
                        "title" to out.title,
                        "content_html" to out.text,
                    ),
                )
                BoardActionResult("boardArticle", ok = true, generalId = c.generalId)
            }
        }
    }

    // ── j_board_comment_add.php ────────────────────────────────────────────────────────────────
    fun handleComment(c: TurnDaemonCommand.BoardComment): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return BoardActionResult("boardComment", ok = false, generalId = c.generalId, reason = "장수가 존재하지 않습니다.")
        if (AccessLogThrottle(world, recorder, nowProvider).increaseAndBlocked(c.generalId)) {
            return BoardActionResult("boardComment", ok = false, generalId = c.generalId, reason = "접속 제한입니다.")
        }

        // PHP 순서 :32-72 — 결합된 null 게이트가 FIRST (`$articleNo === null || $text === null` →
        // '올바르지 않은 입력입니다.'), THEN text trim/blank, THEN 게시물 읽기, THEN 권한 게이트.
        val articleNo = c.articleNo
        if (articleNo == null || c.text == null) {
            return BoardActionResult("boardComment", ok = false, generalId = c.generalId, reason = "올바르지 않은 입력입니다.")
        }
        val text = when (val t = BoardActions.validateCommentText(c.text)) {
            is BoardActions.CommentTextOutcome.Denied ->
                return BoardActionResult("boardComment", ok = false, generalId = c.generalId, reason = t.reason)
            is BoardActions.CommentTextOutcome.Ok -> t.text
        }

        // `SELECT board WHERE no = articleNo AND nation_no = me.nation` → 없을 때 '게시물이 없습니다.'.
        // 조회된 게시물의 `is_secret`이 곧 댓글의 secret 플래그이자 권한 게이트 입력값이다.
        val article = boardPostRepository.findByIdAndNationId(articleNo, me.nationId)
            ?: return BoardActionResult("boardComment", ok = false, generalId = c.generalId, reason = "게시물이 없습니다.")

        val permission = SecretPermission.check(PerTurnOverlay.toLogicGeneral(me))
        BoardActions.commentPermissionDeny(article.isSecret, permission)?.let { reason ->
            return BoardActionResult("boardComment", ok = false, generalId = c.generalId, reason = reason)
        }

        recorder.recordBoardCommentInsert(
            linkedMapOf(
                "post_id" to articleNo,
                "nation_id" to me.nationId,
                "is_secret" to article.isSecret,
                "author_general_id" to me.id,
                "author_name" to me.name,
                "content_text" to text,
            ),
        )
        return BoardActionResult("boardComment", ok = true, generalId = c.generalId)
    }
}
