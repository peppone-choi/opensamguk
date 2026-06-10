package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * F4 READ-only JPA mapping of the `vote_poll` / `vote` / `vote_comment` rows (V1 baseline) for the
 * 설문 조사 page.
 *
 * All three tables EXIST but carry ZERO rows in the fresh scenario_1010 seed — the list/detail
 * controllers return empty gracefully (200, no fabrication). `options` is a jsonb list; `selection`
 * a jsonb list of chosen option indices. game-api ONLY (§7); never written here.
 */
@Entity
@Table(name = "vote_poll")
class VotePollReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "title")
    var title: String = "",

    @Column(name = "body")
    var body: String = "",

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "options", columnDefinition = "jsonb")
    var options: Map<String, Any?> = linkedMapOf(),

    @Column(name = "multiple_options")
    var multipleOptions: Int = 1,

    @Column(name = "reveal_mode")
    var revealMode: String = "",

    @Column(name = "opener_general_id")
    var openerGeneralId: Int = 0,

    @Column(name = "opener_name")
    var openerName: String = "",

    @Column(name = "start_at")
    var startAt: Instant = Instant.EPOCH,

    @Column(name = "end_at")
    var endAt: Instant? = null,

    @Column(name = "closed_at")
    var closedAt: Instant? = null,
)

@Entity
@Table(name = "vote")
class VoteReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "vote_id")
    var voteId: Int = 0,

    @Column(name = "general_id")
    var generalId: Int = 0,

    @Column(name = "nation_id")
    var nationId: Int = 0,

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "selection", columnDefinition = "jsonb")
    var selection: Map<String, Any?> = linkedMapOf(),
)

@Entity
@Table(name = "vote_comment")
class VoteCommentReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "vote_id")
    var voteId: Int = 0,

    @Column(name = "general_id")
    var generalId: Int = 0,

    @Column(name = "nation_id")
    var nationId: Int = 0,

    @Column(name = "general_name")
    var generalName: String = "",

    @Column(name = "nation_name")
    var nationName: String = "",

    @Column(name = "text")
    var text: String = "",

    @Column(name = "created_at")
    var createdAt: Instant = Instant.EPOCH,
)

interface VotePollReadRepository : JpaRepository<VotePollReadEntity, Int> {
    /** All polls, newest first. */
    fun findAllByOrderByIdDesc(): List<VotePollReadEntity>

    /**
     * W0-2(P1-002) — 마지막 설문 폴 1행. PHP의 game_env.lastVote(마지막 설문 id) 대체 원천:
     * vote_poll이 opensamguk 설문 정본([countOpenPolls] 주석의 기존 대체 규약과 동일). 0행 → null.
     */
    fun findFirstByOrderByIdDesc(): VotePollReadEntity?

    /**
     * W3 FrontGlobalInfo `vote` 게이트 — 아직 열려있는(미종료, 미만료) 설문 폴 수.
     * PHP는 game_env.lastVote가 가리키는 폴의 `endDate < now`를 검사한다(GetFrontInfo.php:182-189).
     * opensamguk엔 game_env.lastVote가 채워지지 않으므로(§2) 대체로 `closed_at IS NULL` AND
     * (`end_at IS NULL` OR `end_at > now`)인 폴 존재 여부로 진행중 설문을 판단한다. 폴이 0행인
     * scenario_1010 시드에선 항상 0(false)이라 날조가 아니다. OR 우선순위 모호성을 피해 명시 JPQL 사용.
     */
    @Query(
        "select count(p) from VotePollReadEntity p " +
            "where p.closedAt is null and (p.endAt is null or p.endAt > :now)",
    )
    fun countOpenPolls(@Param("now") now: Instant): Long
}

interface VoteReadRepository : JpaRepository<VoteReadEntity, Int> {
    /** All cast votes for a poll. */
    fun findByVoteId(voteId: Int): List<VoteReadEntity>

    /**
     * W0-2(P1-002) — 호출 장수의 마지막 투표 행(PHP GetFrontInfo.php:578
     * `SELECT vote_id FROM vote WHERE general_id = %i ORDER BY vote_id DESC LIMIT 1`). 없으면 null.
     */
    fun findFirstByGeneralIdOrderByVoteIdDesc(generalId: Int): VoteReadEntity?
}

interface VoteCommentReadRepository : JpaRepository<VoteCommentReadEntity, Int> {
    /** Comments for a poll, oldest first. */
    fun findByVoteIdOrderByCreatedAtAscIdAsc(voteId: Int): List<VoteCommentReadEntity>
}
