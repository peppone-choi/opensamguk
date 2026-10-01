package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.stereotype.Repository
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import org.springframework.data.repository.Repository as SpringDataRepository

@Entity
@Table(name = "board_post")
class BoardPostReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "world_id")
    var worldId: Int = 0,

    @Column(name = "nation_id")
    var nationId: Int = 0,

    @Column(name = "is_secret")
    var isSecret: Boolean = false,

    @Column(name = "author_general_id")
    var authorGeneralId: Int = 0,

    @Column(name = "author_name")
    var authorName: String = "",

    @Column(name = "title")
    var title: String = "",

    @Column(name = "content_html")
    var contentHtml: String = "",

    @Column(name = "created_at")
    var createdAt: Instant = Instant.EPOCH,

    /** ADR-LITE-049 14 — 글 종류(general|vote|operation|notice, V53). */
    @Column(name = "kind")
    var kind: String = "general",

    /** kind=vote 일 때 연결된 vote_poll id(V53). */
    @Column(name = "vote_id")
    var voteId: Int? = null,

    /** kind=operation 일 때 연결된 작전 id(V56, Phase 4X-B). */
    @Column(name = "operation_id")
    var operationId: Int? = null,
)

@Entity
@Table(name = "board_comment")
class BoardCommentReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "world_id")
    var worldId: Int = 0,

    @Column(name = "post_id")
    var postId: Int = 0,

    @Column(name = "nation_id")
    var nationId: Int = 0,

    @Column(name = "is_secret")
    var isSecret: Boolean = false,

    @Column(name = "author_general_id")
    var authorGeneralId: Int = 0,

    @Column(name = "author_name")
    var authorName: String = "",

    @Column(name = "content_text")
    var contentText: String = "",

    @Column(name = "created_at")
    var createdAt: Instant = Instant.EPOCH,
)

interface BoardPostReadRawRepository : SpringDataRepository<BoardPostReadEntity, Int> {
    /** 월드·소속·방·종류와 커서 조건을 모두 결합한 뒤 페이지 크기를 적용한다. */
    @Query(
        "select p from BoardPostReadEntity p where p.worldId = :worldId and p.nationId = :nationId " +
            "and p.isSecret = :secret and p.kind in :kinds " +
            "and (:beforeTime is null or p.createdAt < :beforeTime " +
            "or (p.createdAt = :beforeTime and p.id < :beforeId)) " +
            "order by p.createdAt desc, p.id desc",
    )
    fun findCouncilPage(
        @Param("worldId") worldId: Int, @Param("nationId") nationId: Int,
        @Param("secret") secret: Boolean, @Param("kinds") kinds: Collection<String>,
        @Param("beforeTime") beforeTime: Instant?, @Param("beforeId") beforeId: Int?,
        pageable: Pageable,
    ): List<BoardPostReadEntity>

    fun findByWorldIdAndNationIdAndIsSecretOrderByCreatedAtDescIdDesc(
        worldId: Int, nationId: Int, isSecret: Boolean,
    ): List<BoardPostReadEntity>
    fun findByWorldIdAndIsSecretOrderByCreatedAtDescIdDesc(worldId: Int, isSecret: Boolean): List<BoardPostReadEntity>
    fun findByWorldIdAndId(worldId: Int, id: Int): BoardPostReadEntity?
    fun findByWorldIdAndOperationIdInOrderByIdDesc(worldId: Int, operationIds: Collection<Int>): List<BoardPostReadEntity>
}

@Repository
class BoardPostReadRepository(
    private val raw: BoardPostReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(nationId: Int, isSecret: Boolean): List<BoardPostReadEntity> =
        raw.findByWorldIdAndNationIdAndIsSecretOrderByCreatedAtDescIdDesc(worldId.value, nationId, isSecret)

    fun findByIsSecretOrderByCreatedAtDescIdDesc(isSecret: Boolean): List<BoardPostReadEntity> =
        raw.findByWorldIdAndIsSecretOrderByCreatedAtDescIdDesc(worldId.value, isSecret)

    fun findById(id: Int): java.util.Optional<BoardPostReadEntity> =
        java.util.Optional.ofNullable(raw.findByWorldIdAndId(worldId.value, id))

    fun councilPage(nationId: Int, secret: Boolean, kinds: Collection<String>, beforeTime: Instant?,
                    beforeId: Int?, limit: Int): List<BoardPostReadEntity> {
        require(nationId > 0 && kinds.isNotEmpty() && limit in 1..51)
        require((beforeTime == null) == (beforeId == null) && (beforeId == null || beforeId > 0))
        return raw.findCouncilPage(worldId.value, nationId, secret, kinds, beforeTime, beforeId,
            PageRequest.of(0, limit))
    }

    /** Phase 4X-B — 작전에 연결된 회의실 글(id 내림차순). */
    fun findByOperationIds(operationIds: Collection<Int>): List<BoardPostReadEntity> =
        if (operationIds.isEmpty()) emptyList() else raw.findByWorldIdAndOperationIdInOrderByIdDesc(worldId.value, operationIds)
}

interface BoardCommentReadRawRepository : SpringDataRepository<BoardCommentReadEntity, Int> {
    fun findByWorldIdAndPostIdOrderByCreatedAtAscIdAsc(worldId: Int, postId: Int): List<BoardCommentReadEntity>
}

@Repository
class BoardCommentReadRepository(
    private val raw: BoardCommentReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findByPostIdOrderByCreatedAtAscIdAsc(postId: Int): List<BoardCommentReadEntity> =
        raw.findByWorldIdAndPostIdOrderByCreatedAtAscIdAsc(worldId.value, postId)
}

/** ADR-LITE-049 14 — 기밀실 열람 기록 한 행(V53 board_post_read). 같은 (post, general) 은 첫 열람만 남는다. */
@Entity
@Table(name = "board_post_read")
class BoardPostReadLogEntity(
    @Id
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "world_id")
    var worldId: Int = 0,

    @Column(name = "post_id")
    var postId: Int = 0,

    @Column(name = "general_id")
    var generalId: Int = 0,

    @Column(name = "read_at")
    var readAt: Instant = Instant.EPOCH,
)

interface BoardPostReadLogRawRepository : SpringDataRepository<BoardPostReadLogEntity, Long> {
    fun findByWorldIdAndPostIdInOrderByReadAtAscIdAsc(worldId: Int, postIds: Collection<Int>): List<BoardPostReadLogEntity>
}

@Repository
class BoardPostReadLogRepository(
    private val raw: BoardPostReadLogRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findByPostIds(postIds: Collection<Int>): List<BoardPostReadLogEntity> =
        if (postIds.isEmpty()) emptyList() else raw.findByWorldIdAndPostIdInOrderByReadAtAscIdAsc(worldId.value, postIds)
}
