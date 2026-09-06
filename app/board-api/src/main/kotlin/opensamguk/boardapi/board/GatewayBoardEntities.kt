package opensamguk.boardapi.board

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "gateway_board_post")
open class GatewayBoardPostEntity(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    open var category: GatewayBoardCategory,

    @Column(name = "author_account_id")
    open var authorAccountId: Long?,

    @Column(name = "author_name", nullable = false, length = 50)
    open var authorName: String,

    @Column(nullable = false, length = 120)
    open var title: String,

    @Column(name = "content_html", nullable = false, columnDefinition = "text")
    open var contentHtml: String,

    @Column(nullable = false)
    open var pinned: Boolean = false,

    @Column(name = "pinned_at")
    open var pinnedAt: Instant? = null,

    @Column(name = "deleted_at")
    open var deletedAt: Instant? = null,

    @Column(name = "deleted_by_account_id")
    open var deletedByAccountId: Long? = null,

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant = Instant.now(),

    /** ADR-LITE-049 13 — 조회수(V54). 상세 조회마다 1 증가(UPDATE … view_count + 1). */
    @Column(name = "view_count", nullable = false)
    open var viewCount: Int = 0,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,
) {
    protected constructor() : this(
        category = GatewayBoardCategory.FREE,
        authorAccountId = null,
        authorName = "",
        title = "",
        contentHtml = "",
    )
}

@Entity
@Table(name = "gateway_board_comment")
open class GatewayBoardCommentEntity(
    @Column(name = "post_id", nullable = false)
    open var postId: Long,

    @Column(name = "author_account_id")
    open var authorAccountId: Long?,

    @Column(name = "author_name", nullable = false, length = 50)
    open var authorName: String,

    @Column(name = "content_text", nullable = false, columnDefinition = "text")
    open var contentText: String,

    @Column(name = "deleted_at")
    open var deletedAt: Instant? = null,

    @Column(name = "deleted_by_account_id")
    open var deletedByAccountId: Long? = null,

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,
) {
    protected constructor() : this(
        postId = 0,
        authorAccountId = null,
        authorName = "",
        contentText = "",
    )
}

/** ADR-LITE-049 13 — 글/댓글 신고(V54). post_id 와 comment_id 중 정확히 하나. */
@Entity
@Table(name = "gateway_board_report")
open class GatewayBoardReportEntity(
    @Column(name = "post_id")
    open var postId: Long?,

    @Column(name = "comment_id")
    open var commentId: Long?,

    @Column(name = "reporter_account_id", nullable = false)
    open var reporterAccountId: Long,

    @Column(nullable = false, length = 200)
    open var reason: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    open var status: GatewayBoardReportStatus = GatewayBoardReportStatus.OPEN,

    @Column(name = "handled_by_account_id")
    open var handledByAccountId: Long? = null,

    @Column(name = "handled_at")
    open var handledAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,
)
