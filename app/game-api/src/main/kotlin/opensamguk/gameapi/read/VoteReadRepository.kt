package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import org.springframework.data.repository.Repository as SpringDataRepository

@Entity
@Table(name = "vote_poll")
class VotePollReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "world_id")
    var worldId: Int = 0,

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

    @Column(name = "world_id")
    var worldId: Int = 0,

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

    @Column(name = "world_id")
    var worldId: Int = 0,

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

interface VotePollReadRawRepository : SpringDataRepository<VotePollReadEntity, Int> {
    fun findByWorldIdOrderByIdDesc(worldId: Int): List<VotePollReadEntity>
    fun findFirstByWorldIdOrderByIdDesc(worldId: Int): VotePollReadEntity?
    fun findByWorldIdAndId(worldId: Int, id: Int): VotePollReadEntity?

    @Query(
        "select count(p) from VotePollReadEntity p " +
            "where p.worldId = :worldId and p.closedAt is null and (p.endAt is null or p.endAt > :now)",
    )
    fun countOpenPolls(@Param("worldId") worldId: Int, @Param("now") now: Instant): Long
}

@Repository
class VotePollReadRepository(
    private val raw: VotePollReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findAllByOrderByIdDesc(): List<VotePollReadEntity> = raw.findByWorldIdOrderByIdDesc(worldId.value)
    fun findFirstByOrderByIdDesc(): VotePollReadEntity? = raw.findFirstByWorldIdOrderByIdDesc(worldId.value)
    fun findById(id: Int): java.util.Optional<VotePollReadEntity> =
        java.util.Optional.ofNullable(raw.findByWorldIdAndId(worldId.value, id))
    fun countOpenPolls(now: Instant): Long = raw.countOpenPolls(worldId.value, now)
}

interface VoteReadRawRepository : SpringDataRepository<VoteReadEntity, Int> {
    fun findByWorldIdAndVoteId(worldId: Int, voteId: Int): List<VoteReadEntity>
    fun findFirstByWorldIdAndGeneralIdOrderByVoteIdDesc(worldId: Int, generalId: Int): VoteReadEntity?
}

@Repository
class VoteReadRepository(
    private val raw: VoteReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findByVoteId(voteId: Int): List<VoteReadEntity> = raw.findByWorldIdAndVoteId(worldId.value, voteId)
    fun findFirstByGeneralIdOrderByVoteIdDesc(generalId: Int): VoteReadEntity? =
        raw.findFirstByWorldIdAndGeneralIdOrderByVoteIdDesc(worldId.value, generalId)
}

interface VoteCommentReadRawRepository : SpringDataRepository<VoteCommentReadEntity, Int> {
    fun findByWorldIdAndVoteIdOrderByCreatedAtAscIdAsc(worldId: Int, voteId: Int): List<VoteCommentReadEntity>
}

@Repository
class VoteCommentReadRepository(
    private val raw: VoteCommentReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findByVoteIdOrderByCreatedAtAscIdAsc(voteId: Int): List<VoteCommentReadEntity> =
        raw.findByWorldIdAndVoteIdOrderByCreatedAtAscIdAsc(worldId.value, voteId)
}
