package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.VoteCommentDto
import opensamguk.gameapi.dto.VoteDetailResponse
import opensamguk.gameapi.dto.VoteInfo
import opensamguk.gameapi.dto.VoteListResponse
import opensamguk.gameapi.dto.formatVoteDate
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.VoteCommentReadRepository
import opensamguk.gameapi.read.VotePollReadRepository
import opensamguk.gameapi.read.VoteReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * D9-D10 — `GET /api/votes` (list) + `GET /api/votes/{id}` (detail), 설문 조사. READ-only.
 *
 * Shapes mirror PHP `GetVoteList` / `GetVoteDetail` verbatim:
 *   - List: `{result:true, votes: Map<Int, VoteInfo>}` (voteID key, insertion-ordered)
 *   - Detail: `{result, voteInfo, votes, comments, myVote, userCnt}`
 *
 * `vote_poll`/`vote`/`vote_comment` EXIST but carry ZERO rows in the fresh seed → empty map / 404 on
 * detail of a missing poll, never 500, no fabrication.
 */
@RestController
@RequestMapping("/api/votes")
class VoteController(
    private val polls: VotePollReadRepository,
    private val votes: VoteReadRepository,
    private val comments: VoteCommentReadRepository,
    private val generals: opensamguk.gameapi.read.GeneralReadRepository,
    private val resolver: GeneralResolver,
) {

    /** D9 — GET /api/votes. Returns voteID-keyed LinkedHashMap (newest first). */
    @GetMapping
    fun list(): ResponseEntity<VoteListResponse> {
        val voteMap = linkedMapOf<Int, VoteInfo>()
        for (p in polls.findAllByOrderByIdDesc()) {
            // PHP VoteInfo.options = array_values(options) — 값(텍스트) 삽입순서.
            // null 값은 옵션 키로 폴백한다(plan D9 (e): e.value?.toString() ?: e.key).
            val optionTexts = p.options.entries.map { e -> e.value?.toString() ?: e.key }
            voteMap[p.id] = VoteInfo(
                id = p.id,
                title = p.title,
                multipleOptions = p.multipleOptions,
                opener = p.openerName.takeIf { it.isNotEmpty() },
                startDate = formatVoteDate(p.startAt) ?: "",
                endDate = formatVoteDate(p.endAt),
                options = optionTexts,
            )
        }
        return ResponseEntity.ok(VoteListResponse(votes = voteMap))
    }

    /** D10 — GET /api/votes/{id}. */
    @GetMapping("/{id}")
    fun detail(
        @PathVariable id: Int,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<VoteDetailResponse> {
        val poll = polls.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()

        // Option texts in insertion order (PHP VoteInfo.options = array_values of options map).
        // null 값은 옵션 키로 폴백(plan D9/D10 (e): e.value?.toString() ?: e.key).
        val optionTexts = poll.options.entries.map { e -> e.value?.toString() ?: e.key }

        val voteInfo = VoteInfo(
            id = poll.id,
            title = poll.title,
            multipleOptions = poll.multipleOptions,
            opener = poll.openerName.takeIf { it.isNotEmpty() },
            startDate = formatVoteDate(poll.startAt) ?: "",
            endDate = formatVoteDate(poll.endAt),
            options = optionTexts,
        )

        val cast = votes.findByVoteId(id)

        // votes = GROUP BY selection — each entry is [selectionList, count].
        // PHP: array_map(fn ($arr) => [Json::decode($arr[0]), $arr[1]], ...)
        val selectionGroups = cast
            .groupBy { it.selection.values.mapNotNull { v -> (v as? Number)?.toInt() } }
            .map { (selection, rows) -> listOf(selection, rows.size) }

        // myVote — the calling general's selection indices; null if not voted / no principal.
        val myGeneralId = userId?.let { resolver.resolveGeneralId(it) }
        val myVote: List<Int>? = if (myGeneralId != null) {
            cast.firstOrNull { it.generalId == myGeneralId }
                ?.selection?.values?.mapNotNull { (it as? Number)?.toInt() }
        } else {
            null
        }

        // comments — mirrors PHP VoteComment DTO (id/voteID/generalID/nationID/nationName/generalName/text/date).
        // PHP GetVoteDetail.php:52 = `ORDER BY id ASC` (id-only). 기존 repo 메서드는 created_at 우선이라
        // 컨트롤러에서 id ASC로 재정렬해 동결 회귀 행 순서를 맞춘다(repo 파일은 이 번들 소유 밖).
        val commentRows = comments.findByVoteIdOrderByCreatedAtAscIdAsc(id)
            .sortedBy { it.id }
            .map { c ->
                VoteCommentDto(
                    id = c.id,
                    voteID = c.voteId,
                    generalID = c.generalId,
                    nationID = c.nationId,
                    nationName = c.nationName,
                    generalName = c.generalName,
                    text = c.text,
                    date = formatVoteDate(c.createdAt) ?: "",
                )
            }

        // userCnt = general WHERE npc_state < 2 (PHP `SELECT count(*) FROM general WHERE npc < 2`).
        val userCnt = generals.countByNpcStateLessThan(2).toInt()

        return ResponseEntity.ok(
            VoteDetailResponse(
                result = true,
                voteInfo = voteInfo,
                votes = selectionGroups,
                comments = commentRows,
                myVote = myVote,
                userCnt = userCnt,
            ),
        )
    }
}
