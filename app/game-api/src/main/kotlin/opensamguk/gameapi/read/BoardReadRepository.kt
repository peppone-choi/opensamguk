package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * F4 READ-only JPA mapping of the `board_post` / `board_comment` rows (V1 baseline) for the
 * 회의실 / 기밀실 page.
 *
 * Both tables EXIST but carry ZERO rows in the fresh scenario_1010 seed — the controller returns an
 * empty articles list gracefully (200, no fabrication). The `is_secret` flag splits 회의실(false) /
 * 기밀실(true). game-api ONLY (§7); never written here (board posts are intake, deferred past F4).
 */
@Entity
@Table(name = "board_post")
class BoardPostReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

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

interface BoardPostReadRepository : JpaRepository<BoardPostReadEntity, Int> {
    /** Posts for a nation at a given secrecy tier, newest first. */
    fun findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(nationId: Int, isSecret: Boolean): List<BoardPostReadEntity>

    /** All posts at a given secrecy tier (no nation scope; for an anonymous/global read), newest first. */
    fun findByIsSecretOrderByCreatedAtDescIdDesc(isSecret: Boolean): List<BoardPostReadEntity>
}

interface BoardCommentReadRepository : JpaRepository<BoardCommentReadEntity, Int> {
    /** Comments for a post, oldest first (chronological thread order). */
    fun findByPostIdOrderByCreatedAtAscIdAsc(postId: Int): List<BoardCommentReadEntity>
}
