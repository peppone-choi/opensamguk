package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.VoteCommentRow
import opensamguk.gameapi.dto.VoteDetailResponse
import opensamguk.gameapi.dto.VoteOptionResult
import opensamguk.gameapi.dto.VoteSummary
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
 * F4 — `GET /api/votes` (list) + `GET /api/votes/{id}` (detail), 설문 조사 (spec page 5). READ-only.
 *
 * `vote_poll`/`vote`/`vote_comment` EXIST but carry ZERO rows in the fresh seed → empty list / 404 on
 * detail of a missing poll, never 500, no fabrication. Detail tallies per-option `count` from the cast
 * `vote.selection` index lists, `userCnt` = distinct voters, `myVote` = the calling general's selection
 * (resolved from the verified principal; empty if anonymous / not voted), plus the comment thread.
 *
 * Public list/detail (the 설문 results are nation-visible); identity only supplements `myVote`.
 */
@RestController
@RequestMapping("/api/votes")
class VoteController(
    private val polls: VotePollReadRepository,
    private val votes: VoteReadRepository,
    private val comments: VoteCommentReadRepository,
    private val resolver: GeneralResolver,
) {

    @GetMapping
    fun list(): ResponseEntity<List<VoteSummary>> {
        val rows = polls.findAllByOrderByIdDesc().map { p ->
            VoteSummary(
                id = p.id,
                title = p.title,
                openerName = p.openerName,
                multipleOptions = p.multipleOptions,
                startAt = p.startAt,
                endAt = p.endAt,
                closed = p.closedAt != null,
            )
        }
        return ResponseEntity.ok(rows)
    }

    @GetMapping("/{id}")
    fun detail(
        @PathVariable id: Int,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<VoteDetailResponse> {
        val poll = polls.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()

        // Option labels: vote_poll.options is an insertion-ordered jsonb map (index -> text).
        val optionTexts = poll.options.entries.toList()
        val cast = votes.findByVoteId(id)

        // Tally per option index from each cast selection (selection is a jsonb list of chosen indices).
        val counts = IntArray(optionTexts.size)
        for (v in cast) {
            for ((_, sel) in v.selection) {
                (sel as? Number)?.toInt()?.let { idx -> if (idx in counts.indices) counts[idx]++ }
            }
        }
        val optionResults = optionTexts.mapIndexed { idx, e ->
            VoteOptionResult(index = idx, text = e.value?.toString() ?: e.key, count = counts.getOrElse(idx) { 0 })
        }

        // myVote — the calling general's selection indices (resolved from principal); empty otherwise.
        val myGeneralId = userId?.let { resolver.resolveGeneralId(it) }
        val myVote: List<Int> = if (myGeneralId != null) {
            cast.firstOrNull { it.generalId == myGeneralId }
                ?.selection?.values?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        } else {
            emptyList()
        }

        val commentRows = comments.findByVoteIdOrderByCreatedAtAscIdAsc(id).map { c ->
            VoteCommentRow(
                id = c.id,
                generalName = c.generalName,
                nationName = c.nationName,
                text = c.text,
                date = c.createdAt,
            )
        }

        return ResponseEntity.ok(
            VoteDetailResponse(
                result = true,
                id = poll.id,
                title = poll.title,
                body = poll.body,
                openerName = poll.openerName,
                multipleOptions = poll.multipleOptions,
                startAt = poll.startAt,
                endAt = poll.endAt,
                closed = poll.closedAt != null,
                options = optionResults,
                userCnt = cast.map { it.generalId }.distinct().size,
                myVote = myVote,
                comments = commentRows,
            ),
        )
    }
}
