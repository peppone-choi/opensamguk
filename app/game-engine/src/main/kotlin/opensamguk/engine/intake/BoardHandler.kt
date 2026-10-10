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
import opensamguk.logic.input.RuleProfile
import java.time.Instant

/**
 * 회의실/기밀실 (board) intake 핸들러 — `j_board_article_add.php` /
 * `j_board_comment_add.php` `launch()` 본문의 faithful 포팅 (F4 Wave C2 slice C). per-run (world + recorder +
 * board_post read repo), [TroopHandler]를 미러링.
 *
 * 순수 검증 + 권한 게이트는 [BoardActions]에 있다. 이 핸들러는 PHP가 `DB::db()`로 수행하는
 * 부수 효과를 담당한다: 댓글의 게시물 존재 여부 `SELECT board WHERE no AND nation_no`
 * ([boardPostRepository]를 통해, 게시물의 `is_secret`을 산출) 와 board_post/board_comment
 * INSERT로, [ChangeRecorder]의 social-content 채널에 기록된다 (betting을 미러링 —
 * board 게시물은 InMemoryTurnWorld 게임 상태가 아니므로 world create/update 경로를 절대 건드리지 않는다).
 *
 * Product secret access and notices use current Council authority. The archive keeps its frozen permission gate. RNG-free.
 */
class BoardHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val boardPostRepository: BoardPostRepository,
    private val nowProvider: () -> Instant = Instant::now,
    private val authority: CouncilExecutionAuthoritySource = CouncilWorldAuthoritySource(world),
) {
    // ── j_board_article_add.php ────────────────────────────────────────────────────────────────
    fun handleArticle(c: TurnDaemonCommand.BoardArticle): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return BoardActionResult("boardArticle", ok = false, generalId = c.generalId, reason = "장수가 존재하지 않습니다.")
        val permission = SecretPermission.check(PerTurnOverlay.toLogicGeneral(me))
        // Archive permission values retain their meaning; product ACLs are independent predicates.
        val validation = if (world.ruleProfile == RuleProfile.HWIHA && (c.isSecret || c.kind == BoardActions.KIND_NOTICE))
            validateProtectedArticle(c, me.nationId) else null
        if (validation is BoardActions.ArticleOutcome.Denied)
            return BoardActionResult("boardArticle", ok = false, generalId = c.generalId, reason = validation.reason)
        if (AccessLogThrottle(world, recorder, nowProvider).increaseAndBlocked(c.generalId)) {
            return BoardActionResult("boardArticle", ok = false, generalId = c.generalId, reason = "접속 제한입니다.")
        }
        return when (val out = validation ?: BoardActions.addArticle(c.isSecret, c.title, c.text, permission, c.kind, c.voteId, c.operationId)) {
            is BoardActions.ArticleOutcome.Denied ->
                BoardActionResult("boardArticle", ok = false, generalId = c.generalId, reason = out.reason)
            is BoardActions.ArticleOutcome.Insert -> {
                // Phase 4X-B(N6): 연결 작전은 내 국가의 것이어야 한다(world 조회라 handler 소관). 같은 틱 선언도 허용(DEFERRABLE FK).
                val linkedOperationId = out.operationId
                if (linkedOperationId != null) {
                    val op = world.getOperationById(linkedOperationId)
                    if (op == null || op.nationId != me.nationId) {
                        return BoardActionResult("boardArticle", ok = false, generalId = c.generalId, reason = "작전이 없습니다.")
                    }
                }
                recorder.recordBoardPostInsert(
                    linkedMapOf(
                        "nation_id" to me.nationId,
                        "is_secret" to out.isSecret,
                        "author_general_id" to me.id,
                        "author_name" to me.name,
                        "title" to out.title,
                        "content_html" to out.text,
                        "kind" to out.kind,
                        "vote_id" to out.voteId,
                        "operation_id" to out.operationId,
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
        val hwiha = world.ruleProfile == RuleProfile.HWIHA
        if (!hwiha && AccessLogThrottle(world, recorder, nowProvider).increaseAndBlocked(c.generalId)) {
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
        if (hwiha && (articleNo <= 0 || me.nationId <= 0)) {
            return BoardActionResult("boardComment", ok = false, generalId = c.generalId, reason = "게시물이 없습니다.")
        }
        val proof = if (hwiha) currentAuthority(me.nationId) else CouncilExecutionAuthority()
        val article = if (hwiha) boardPostRepository.findAccessibleCouncilPost(articleNo, me.nationId, me.id in proof.writers)
            else boardPostRepository.findByIdAndNationId(articleNo, me.nationId)
        if (article == null || article.id != articleNo || article.nationId != me.nationId) {
            return BoardActionResult("boardComment", ok = false, generalId = c.generalId, reason = "게시물이 없습니다.")
        }
        val permission = SecretPermission.check(PerTurnOverlay.toLogicGeneral(me))
        val denied = if (hwiha && article.isSecret) {
            if (me.id in proof.writers) null else "권한이 부족합니다. 수뇌부가 아닙니다."
        } else BoardActions.commentPermissionDeny(article.isSecret, permission)
        denied?.let { reason ->
            return BoardActionResult("boardComment", ok = false, generalId = c.generalId, reason = reason)
        }

        if (hwiha && AccessLogThrottle(world, recorder, nowProvider).increaseAndBlocked(c.generalId)) {
            return BoardActionResult("boardComment", ok = false, generalId = c.generalId, reason = "접속 제한입니다.")
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

    /**
     * 기밀실 열람 기록(ADR-LITE-049 14). 글이 내 국가의 것이고 권한 게이트를 통과하면 board_post_read 에
     * (post_id, general_id) 를 남긴다. 회의실(비밀 아님) 글은 기록하지 않고 ok 만 돌려준다.
     */
    fun handleRead(c: TurnDaemonCommand.BoardRead): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return BoardActionResult("boardRead", ok = false, generalId = c.generalId, reason = "장수가 존재하지 않습니다.")
        val articleNo = c.articleNo
            ?: return BoardActionResult("boardRead", ok = false, generalId = c.generalId, reason = "올바르지 않은 입력입니다.")
        val hwiha = world.ruleProfile == RuleProfile.HWIHA
        if (hwiha && (articleNo <= 0 || me.nationId <= 0)) {
            return BoardActionResult("boardRead", ok = false, generalId = c.generalId, reason = "게시물이 없습니다.")
        }
        val proof = if (hwiha) currentAuthority(me.nationId) else CouncilExecutionAuthority()
        val article = if (hwiha) boardPostRepository.findAccessibleCouncilPost(articleNo, me.nationId, me.id in proof.readers)
            else boardPostRepository.findByIdAndNationId(articleNo, me.nationId)
        if (article == null || article.id != articleNo || article.nationId != me.nationId) {
            return BoardActionResult("boardRead", ok = false, generalId = c.generalId, reason = "게시물이 없습니다.")
        }
        val permission = SecretPermission.check(PerTurnOverlay.toLogicGeneral(me))
        val denied = if (hwiha && article.isSecret) {
            if (me.id in proof.readers) null else "권한이 부족합니다. 수뇌부가 아닙니다."
        } else BoardActions.readPermissionDeny(article.isSecret, permission)
        denied?.let { reason ->
            return BoardActionResult("boardRead", ok = false, generalId = c.generalId, reason = reason)
        }
        if (article.isSecret) {
            recorder.recordBoardReadInsert(linkedMapOf("post_id" to articleNo, "general_id" to me.id))
        }
        return BoardActionResult("boardRead", ok = true, generalId = c.generalId)
    }

    /** Keep the existing wire normalization while replacing only the archive's protected-room gates. */
    private fun validateProtectedArticle(c: TurnDaemonCommand.BoardArticle, nationId: Int): BoardActions.ArticleOutcome {
        val rawTitle = c.title
        val rawText = c.text
        if (rawTitle == null || rawText == null || c.kind !in BoardActions.KINDS ||
            (c.kind == BoardActions.KIND_VOTE && c.voteId == null) ||
            (c.kind != BoardActions.KIND_OPERATION && c.operationId != null))
            return BoardActions.ArticleOutcome.Denied("올바르지 않은 입력입니다.")
        val title = rawTitle.trim()
        val text = rawText.trim()
        if (title.isEmpty() && text.isEmpty())
            return BoardActions.ArticleOutcome.Denied("제목과 내용이 둘다 비어있습니다.")
        if (nationId <= 0) return BoardActions.ArticleOutcome.Denied("국가에 소속되어있지 않습니다.")
        val proof = currentAuthority(nationId)
        if ((c.isSecret && c.generalId !in proof.writers) ||
            (c.kind == BoardActions.KIND_NOTICE && c.generalId !in proof.noticeWriters))
            return BoardActions.ArticleOutcome.Denied("권한이 부족합니다. 수뇌부가 아닙니다.")
        return BoardActions.ArticleOutcome.Insert(c.isSecret, title, text, c.kind,
            c.voteId.takeIf { c.kind == BoardActions.KIND_VOTE },
            c.operationId.takeIf { c.kind == BoardActions.KIND_OPERATION })
    }

    private fun currentAuthority(nationId: Int): CouncilExecutionAuthority = try {
        authority.read(nationId)
    } catch (_: RuntimeException) { CouncilExecutionAuthority() }
}
