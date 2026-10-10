package opensamguk.gameapi.controller

import opensamguk.gameapi.council.BoardSecretAccessQuery
import opensamguk.gameapi.dto.BoardArticle
import opensamguk.gameapi.dto.BoardComment
import opensamguk.gameapi.dto.BoardParticipant
import opensamguk.gameapi.dto.BoardPerson
import opensamguk.gameapi.dto.BoardReaders
import opensamguk.gameapi.dto.BoardResponse
import opensamguk.gameapi.dto.BoardVoteOption
import opensamguk.gameapi.dto.BoardVoteSummary
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.BoardCommentReadRepository
import opensamguk.gameapi.read.BoardPostReadLogRepository
import opensamguk.gameapi.read.BoardPostReadRepository
import opensamguk.gameapi.read.F4StateText
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.VotePollReadRepository
import opensamguk.gameapi.read.VoteReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * F4 — `GET /api/board?secret={bool}` (회의실 / 기밀실, spec page 4). READ-only.
 *
 * `board_post`/`board_comment` EXIST but carry ZERO rows in the fresh seed → empty articles
 * (`{result:true, articles:[]}`), never 500, no fabrication. Title is verbatim 회의실(secret=false) /
 * 기밀실(secret=true).
 *
 * Both boards are internal to the verified caller's positive nation. Anonymous callers are rejected,
 * nationless callers receive an empty INFO response without falling back to a global query.
 * Same-nation callers without current secret access
 * receive an empty secret board with an INFO reason, without reading board data.
 * Optional nationId only confirms the caller's nation; it never selects another nation's posts.
 *
 * ADR-LITE-049 14 확장: 글 종류(kind)·표결 요약(vote_poll/vote 읽기)·기밀실 열람 기록(board_post_read)·
 * 작성자/댓글 초상(현재 general 행)·회의실 참여 스택(국가 플레이어 장수, 최근 한 순 활동 여부).
 * 표시 값은 전부 읽기 원천에서만 나온다 — 원천이 없으면 null/빈 목록.
 */
@RestController
@RequestMapping("/api/board")
class BoardController(
    private val posts: BoardPostReadRepository,
    private val comments: BoardCommentReadRepository,
    private val resolver: GeneralResolver,
    private val generals: GeneralReadRepository,
    private val votePolls: VotePollReadRepository,
    private val votes: VoteReadRepository,
    private val readLog: BoardPostReadLogRepository,
    private val worldStates: WorldStateReadRepository,
    private val nowProvider: () -> Instant = Instant::now,
    private val secretAccess: BoardSecretAccessQuery? = null,
) {
    @GetMapping
    fun board(
        @RequestParam(name = "secret", defaultValue = "false") secret: Boolean,
        @AuthenticationPrincipal userId: Long?,
        @RequestParam(name = "nationId", required = false) requestedNationId: Int?,
    ): ResponseEntity<BoardResponse> {
        if (userId == null || userId <= 0) return ResponseEntity.status(401).build()
        val resolved = resolver.resolve(userId) ?: return ResponseEntity.status(403).build()
        val nationId = resolved.nationId
        if (requestedNationId != null && requestedNationId != nationId) {
            return ResponseEntity.status(403).build()
        }
        val title = F4StateText.boardTitle(secret)
        if (nationId <= 0) {
            return ResponseEntity.ok(
                BoardResponse(
                    result = true,
                    secret = secret,
                    title = title,
                    articles = emptyList(),
                    blockedReason = "소속 세력이 없어 회의실을 이용할 수 없습니다.",
                    myGeneralId = resolved.general.id,
                    myPermission = -1,
                ),
            )
        }
        val myPermission = resolved.permission
        val secretBlockedReason = if (secret) {
            if (secretAccess == null) BoardSecretAccessQuery.UNAVAILABLE else secretAccess.blockedReason(userId, resolved)
        } else null
        if (secretBlockedReason != null) {
            return ResponseEntity.ok(
                BoardResponse(
                    result = true,
                    secret = true,
                    title = title,
                    articles = emptyList(),
                    blockedReason = secretBlockedReason,
                    myGeneralId = resolved.general.id,
                    myPermission = myPermission,
                ),
            )
        }
        val nationLevel = resolved.nationLevel
        val postRows = posts.findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(nationId, secret)
        // 국가 장수 — 초상·직책·참여 스택·수뇌부 정원의 단일 원천(한 번만 읽는다).
        val nationGenerals: List<GeneralReadEntity> =
            generals.findByNationIdOrderByOfficerLevelDescIdAsc(nationId)
        val byId = HashMap<Int, GeneralReadEntity>(nationGenerals.associateBy { it.id })
        fun personOf(id: Int): BoardPerson? {
            // Historical names come from the post; current metadata stays inside the caller's nation roster.
            val g = byId[id] ?: return null
            return BoardPerson(
                generalId = g.id,
                name = g.name,
                picture = g.picture,
                imageServer = g.imageServer,
                officerLevelText = F4StateText.officerLevelText(g.officerLevel, nationLevel),
            )
        }
        val chiefCount = nationGenerals.count { GeneralResolver.derivePermission(it.officerLevel) >= 2 }
        val eligibleCount = nationGenerals.count { it.npcState < 2 }
        val readsByPost = if (secret) {
            readLog.findByPostIds(postRows.map { it.id }).groupBy { it.postId }
        } else {
            emptyMap()
        }
        val myId = resolved.general.id

        val articles = postRows.map { p ->
            val commentRows = comments.findByPostIdOrderByCreatedAtAscIdAsc(p.id).map { c ->
                val author = personOf(c.authorGeneralId)
                BoardComment(
                    id = c.id,
                    authorGeneralId = c.authorGeneralId,
                    authorName = c.authorName,
                    text = c.contentText,
                    date = c.createdAt,
                    authorPicture = author?.picture,
                    authorImageServer = author?.imageServer ?: 0,
                )
            }
            val author = personOf(p.authorGeneralId)
            BoardArticle(
                id = p.id,
                nationId = p.nationId,
                authorGeneralId = p.authorGeneralId,
                authorName = p.authorName,
                title = p.title,
                contentHtml = p.contentHtml,
                date = p.createdAt,
                comments = commentRows,
                kind = p.kind,
                voteId = p.voteId,
                vote = p.voteId?.let { voteSummary(it, myId, eligibleCount, ::personOf) },
                operationId = p.operationId,
                readers = if (secret) {
                    BoardReaders(
                        read = (readsByPost[p.id] ?: emptyList()).mapNotNull { personOf(it.generalId) },
                        total = chiefCount,
                    )
                } else {
                    null
                },
                authorPicture = author?.picture,
                authorImageServer = author?.imageServer ?: 0,
                authorOfficerLevelText = author?.officerLevelText,
            )
        }
        val tick = worldStates.findProcessWorld()?.tickSeconds ?: 0
        val activeSince = nowProvider().minusSeconds(tick.toLong())
        val participants = nationGenerals.filter { it.npcState < 2 }.map { g ->
            BoardParticipant(
                generalId = g.id,
                name = g.name,
                picture = g.picture,
                imageServer = g.imageServer,
                officerLevelText = F4StateText.officerLevelText(g.officerLevel, nationLevel),
                active = tick > 0 && g.turnTime?.let { !it.isBefore(activeSince) } == true,
                chief = GeneralResolver.derivePermission(g.officerLevel) >= 2,
            )
        }
        return ResponseEntity.ok(
            BoardResponse(
                result = true,
                secret = secret,
                title = title,
                articles = articles,
                blockedReason = null,
                participants = participants,
                chiefCount = chiefCount,
                myGeneralId = myId,
                myPermission = myPermission,
            ),
        )
    }

    /** vote_poll + vote 읽기 → 선택지별 표 수·표결자. 비공개(reveal_mode != public) 표결은 표결자 이름을 숨긴다. */
    private fun voteSummary(
        voteId: Int,
        myId: Int?,
        eligibleCount: Int,
        personOf: (Int) -> BoardPerson?,
    ): BoardVoteSummary? {
        val poll = votePolls.findById(voteId).orElse(null) ?: return null
        val rows = votes.findByVoteId(voteId)
        val selectionOf = { row: opensamguk.gameapi.read.VoteReadEntity ->
            row.selection.values.mapNotNull { v -> (v as? Number)?.toInt() }
        }
        val reveal = poll.revealMode == "public"
        val optionTexts = poll.options.values.map { it?.toString() ?: "" }
        val options = optionTexts.mapIndexed { index, text ->
            val voters = rows.filter { index in selectionOf(it) }
            BoardVoteOption(
                index = index,
                text = text,
                count = voters.size,
                voters = if (reveal) voters.mapNotNull { personOf(it.generalId) } else emptyList(),
            )
        }
        val now = nowProvider()
        val closed = poll.closedAt != null || (poll.endAt?.let { !it.isAfter(now) } ?: false)
        return BoardVoteSummary(
            voteId = poll.id,
            title = poll.title,
            endDate = poll.endAt,
            closed = closed,
            options = options,
            myVote = myId?.let { me -> rows.firstOrNull { it.generalId == me }?.let(selectionOf) },
            voterCount = rows.map { it.generalId }.distinct().size,
            eligibleCount = eligibleCount,
        )
    }
}
