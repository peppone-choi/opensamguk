package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
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
}

interface VoteReadRepository : JpaRepository<VoteReadEntity, Int> {
    /** All cast votes for a poll. */
    fun findByVoteId(voteId: Int): List<VoteReadEntity>
}

interface VoteCommentReadRepository : JpaRepository<VoteCommentReadEntity, Int> {
    /** Comments for a poll, oldest first. */
    fun findByVoteIdOrderByCreatedAtAscIdAsc(voteId: Int): List<VoteCommentReadEntity>
}
