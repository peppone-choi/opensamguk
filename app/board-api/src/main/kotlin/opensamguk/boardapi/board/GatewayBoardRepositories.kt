package opensamguk.boardapi.board

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying

interface GatewayBoardPostRepository : JpaRepository<GatewayBoardPostEntity, Long> {
    /**
     * ADR-LITE-049 13 — 공개 피드 검색(분류·검색어·작성자 선택 필터). 고정 글 우선 → 최신.
     * 필터 부재는 SQL 파라미터 null 로 표현한다(native: `:x IS NULL OR …`).
     */
    @Query(
        value = """
            SELECT p.* FROM gateway_board_post p
             WHERE p.deleted_at IS NULL
               AND (:category IS NULL OR p.category = :category)
               AND (:author IS NULL OR p.author_account_id = :author)
               AND (:q IS NULL OR p.title ILIKE '%' || :q || '%' OR p.content_html ILIKE '%' || :q || '%')
             ORDER BY p.pinned DESC, p.pinned_at DESC NULLS LAST, p.created_at DESC, p.id DESC
        """,
        countQuery = """
            SELECT count(*) FROM gateway_board_post p
             WHERE p.deleted_at IS NULL
               AND (:category IS NULL OR p.category = :category)
               AND (:author IS NULL OR p.author_account_id = :author)
               AND (:q IS NULL OR p.title ILIKE '%' || :q || '%' OR p.content_html ILIKE '%' || :q || '%')
        """,
        nativeQuery = true,
    )
    fun searchLatest(
        @Param("category") category: String?,
        @Param("author") author: Long?,
        @Param("q") q: String?,
        pageable: Pageable,
    ): Page<GatewayBoardPostEntity>

    /**
     * 인기 = 최근 7일 안에 쓴 글을 조회수 + 댓글 수 × 5 로 정렬(코드 상수 — 아트보드 「인기」의 정의).
     * 고정 글은 우선하지 않는다(최신 탭의 규칙).
     */
    @Query(
        value = """
            SELECT p.* FROM gateway_board_post p
             WHERE p.deleted_at IS NULL
               AND p.created_at >= :since
               AND (:category IS NULL OR p.category = :category)
               AND (:q IS NULL OR p.title ILIKE '%' || :q || '%' OR p.content_html ILIKE '%' || :q || '%')
             ORDER BY (p.view_count + 5 * (SELECT count(*) FROM gateway_board_comment c WHERE c.post_id = p.id AND c.deleted_at IS NULL)) DESC,
                      p.created_at DESC, p.id DESC
        """,
        countQuery = """
            SELECT count(*) FROM gateway_board_post p
             WHERE p.deleted_at IS NULL
               AND p.created_at >= :since
               AND (:category IS NULL OR p.category = :category)
               AND (:q IS NULL OR p.title ILIKE '%' || :q || '%' OR p.content_html ILIKE '%' || :q || '%')
        """,
        nativeQuery = true,
    )
    fun searchPopular(
        @Param("category") category: String?,
        @Param("q") q: String?,
        @Param("since") since: java.time.Instant,
        pageable: Pageable,
    ): Page<GatewayBoardPostEntity>

    @Query("select p.category, count(p) from GatewayBoardPostEntity p where p.deletedAt is null group by p.category")
    fun countByCategoryGrouped(): List<Array<Any>>

    @Modifying(clearAutomatically = true)
    @Query("update GatewayBoardPostEntity p set p.viewCount = p.viewCount + 1 where p.id = :id")
    fun incrementViewCount(@Param("id") id: Long): Int

    // 피드는 삭제분을 쿼리에서 걸러낸다. 응답에서만 가리면 묘비가 목록에 남고 페이지네이션 칸까지
    // 먹는다 — 소프트딜리트는 감사 기록용이지 노출용이 아니다.
    fun findByDeletedAtIsNull(pageable: Pageable): Page<GatewayBoardPostEntity>

    fun findByCategoryAndDeletedAtIsNull(
        category: GatewayBoardCategory,
        pageable: Pageable,
    ): Page<GatewayBoardPostEntity>

    // 어드민 감사용 — 삭제분까지 본다. 공개 피드는 위 두 쿼리만 쓴다.
    fun findByCategory(category: GatewayBoardCategory, pageable: Pageable): Page<GatewayBoardPostEntity>
}

interface GatewayBoardCommentRepository : JpaRepository<GatewayBoardCommentEntity, Long> {
    /** 한 페이지의 댓글 수를 한 번의 GROUP BY 로(행마다 세면 N+1). */
    @Query("select c.postId, count(c) from GatewayBoardCommentEntity c where c.deletedAt is null and c.postId in :ids group by c.postId")
    fun countByPostIds(@Param("ids") ids: Collection<Long>): List<Array<Any>>

    // 삭제된 댓글은 묘비도 남기지 않는다 — 글과 같은 규칙이다.
    fun findByPostIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(postId: Long): List<GatewayBoardCommentEntity>
}

interface GatewayBoardReportRepository : JpaRepository<GatewayBoardReportEntity, Long> {
    fun findByStatusOrderByCreatedAtDescIdDesc(status: GatewayBoardReportStatus, pageable: Pageable): Page<GatewayBoardReportEntity>
    fun findAllByOrderByCreatedAtDescIdDesc(pageable: Pageable): Page<GatewayBoardReportEntity>
    fun existsByPostIdAndReporterAccountIdAndStatus(postId: Long, reporterAccountId: Long, status: GatewayBoardReportStatus): Boolean
    fun existsByCommentIdAndReporterAccountIdAndStatus(commentId: Long, reporterAccountId: Long, status: GatewayBoardReportStatus): Boolean
    fun countByStatus(status: GatewayBoardReportStatus): Long
}
