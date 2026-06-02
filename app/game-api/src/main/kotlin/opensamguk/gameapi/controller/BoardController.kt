package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.BoardArticle
import opensamguk.gameapi.dto.BoardComment
import opensamguk.gameapi.dto.BoardResponse
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.BoardCommentReadRepository
import opensamguk.gameapi.read.BoardPostReadRepository
import opensamguk.gameapi.read.F4StateText
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * F4 — `GET /api/board?secret={bool}` (회의실 / 기밀실, spec page 4). READ-only.
 *
 * `board_post`/`board_comment` EXIST but carry ZERO rows in the fresh seed → empty articles
 * (`{result:true, articles:[]}`), never 500, no fabrication. Title is verbatim 회의실(secret=false) /
 * 기밀실(secret=true).
 *
 * checkSecretPermission gate: the 기밀실 (secret=true) requires permission >= 2 (수뇌). When a verified
 * principal is present and lacks it, the response sets `blockedReason` (rendered as INFO, not error)
 * and returns empty articles. An anonymous caller asking for the secret board is also blocked. The
 * public 회의실 (secret=false) is open. Posts are scoped to the caller's nation when resolvable;
 * otherwise the global tier list (still empty in the seed).
 */
@RestController
@RequestMapping("/api/board")
class BoardController(
    private val posts: BoardPostReadRepository,
    private val comments: BoardCommentReadRepository,
    private val resolver: GeneralResolver,
) {

    /** Verbatim 권한 차단 string for the 기밀실 (수뇌 only) gate. */
    private val secretBlockedReason = "권한이 부족합니다. 수뇌부가 아닙니다."

    @GetMapping
    fun board(
        @RequestParam(name = "secret", defaultValue = "false") secret: Boolean,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<BoardResponse> {
        val resolved = userId?.let { resolver.resolve(it) }
        val title = F4StateText.boardTitle(secret)

        if (secret) {
            val allowed = resolved != null && resolved.permission >= 2
            if (!allowed) {
                return ResponseEntity.ok(
                    BoardResponse(
                        result = true,
                        secret = true,
                        title = title,
                        articles = emptyList(),
                        blockedReason = secretBlockedReason,
                    ),
                )
            }
        }

        val nationId = resolved?.nationId ?: 0
        val postRows = if (nationId != 0) {
            posts.findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(nationId, secret)
        } else {
            posts.findByIsSecretOrderByCreatedAtDescIdDesc(secret)
        }

        val articles = postRows.map { p ->
            val commentRows = comments.findByPostIdOrderByCreatedAtAscIdAsc(p.id).map { c ->
                BoardComment(
                    id = c.id,
                    authorGeneralId = c.authorGeneralId,
                    authorName = c.authorName,
                    text = c.contentText,
                    date = c.createdAt,
                )
            }
            BoardArticle(
                id = p.id,
                nationId = p.nationId,
                authorGeneralId = p.authorGeneralId,
                authorName = p.authorName,
                title = p.title,
                contentHtml = p.contentHtml,
                date = p.createdAt,
                comments = commentRows,
            )
        }

        return ResponseEntity.ok(
            BoardResponse(
                result = true,
                secret = secret,
                title = title,
                articles = articles,
                blockedReason = null,
            ),
        )
    }
}
