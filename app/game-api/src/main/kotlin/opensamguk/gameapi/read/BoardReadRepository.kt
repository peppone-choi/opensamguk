package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.stereotype.Repository
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
    fun findByWorldIdAndNationIdAndIsSecretOrderByCreatedAtDescIdDesc(
        worldId: Int, nationId: Int, isSecret: Boolean,
    ): List<BoardPostReadEntity>
    fun findByWorldIdAndIsSecretOrderByCreatedAtDescIdDesc(worldId: Int, isSecret: Boolean): List<BoardPostReadEntity>
    fun findByWorldIdAndId(worldId: Int, id: Int): BoardPostReadEntity?
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
