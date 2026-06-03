package opensamguk.infra.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * `board_post` 테이블용 JPA 엔티티 (회의실/기밀실 글, V1__baseline.sql:351).
 *
 * 데몬 측에서는 읽기 전용 — board 댓글 인테이크 핸들러가 `isSecret`를 읽어 댓글 권한을
 * 게이팅한다 (`j_board_comment_add.php`의 충실한 `SELECT board WHERE no AND nation_no`).
 * 쓰기 경로(board 글/댓글 INSERT)는 [opensamguk.infra.persistence.JdbcFlushExecutor]
 * step-8d를 거치며, 절대 `save()`를 쓰지 않는다 (one-daemon-write 규칙).
 */
@Entity
@Table(name = "board_post")
class BoardPostEntity(
    @Column(name = "nation_id", nullable = false)
    var nationId: Int,

    @Column(name = "is_secret", nullable = false)
    var isSecret: Boolean = false,

    @Column(name = "author_general_id", nullable = false)
    var authorGeneralId: Int,

    @Column(name = "author_name", nullable = false)
    var authorName: String,

    @Column(name = "title", nullable = false)
    var title: String,

    @Column(name = "content_html", nullable = false)
    var contentHtml: String,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,
)
