package opensamguk.boardapi.board

import opensamguk.boardapi.security.BoardUserDetails
import opensamguk.infra.read.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

class GatewayBoardNotFoundException : RuntimeException("게시글을 찾을 수 없습니다.")

class GatewayBoardForbiddenException(message: String) : RuntimeException(message)

class GatewayBoardConflictException(message: String) : RuntimeException(message)

@Service
class GatewayBoardService(
    private val postRepository: GatewayBoardPostRepository,
    private val commentRepository: GatewayBoardCommentRepository,
    private val contentSanitizer: GatewayBoardContentSanitizer,
    private val userRepository: UserRepository,
    private val reportRepository: GatewayBoardReportRepository,
) {

    @Transactional(readOnly = true)
    fun list(
        category: GatewayBoardCategory?,
        page: Int,
        size: Int,
        principal: BoardUserDetails?,
        includeDeleted: Boolean = false,
        sort: GatewayBoardSort = GatewayBoardSort.LATEST,
        query: String? = null,
    ): GatewayBoardPageResponse {
        require(page >= 0) { "page는 0 이상이어야 합니다." }
        require(size in 1..50) { "size는 1부터 50 사이여야 합니다." }
        // 삭제분은 어드민 감사 화면에서만 보인다. 공개 피드가 이 문을 열 수 없게 권한으로 막는다.
        if (includeDeleted && principal?.isAdmin() != true) {
            throw GatewayBoardForbiddenException("삭제된 게시글은 관리자만 조회할 수 있습니다.")
        }
        val q = query?.trim()?.takeIf { it.isNotEmpty() }?.take(100)
        val result = if (includeDeleted) {
            val pageable = PageRequest.of(page, size, FEED_SORT)
            category?.let { postRepository.findByCategory(it, pageable) } ?: postRepository.findAll(pageable)
        } else {
            // ADR-LITE-049 13 — 최신 / 인기(최근 7일 조회+댓글×5) / 내 글. 정렬은 native 쿼리가 소유한다.
            val pageable = PageRequest.of(page, size)
            when (sort) {
                GatewayBoardSort.LATEST -> postRepository.searchLatest(category?.name, null, q, pageable)
                GatewayBoardSort.POPULAR -> postRepository.searchPopular(category?.name, q, Instant.now().minus(POPULAR_WINDOW), pageable)
                GatewayBoardSort.MINE -> {
                    val me = principal ?: throw GatewayBoardForbiddenException("내 글은 로그인 뒤 볼 수 있습니다.")
                    postRepository.searchLatest(category?.name, me.id, q, pageable)
                }
            }
        }
        val authors = authorsOf(result.content.map { it.authorAccountId })
        val commentCounts = commentCountsOf(result.content.mapNotNull { it.id })
        return GatewayBoardPageResponse(
            content = result.content.map { postResponse(it, principal, authors, commentCounts[it.id] ?: 0) },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
    }

    /** 분류별 공개 글 수(6 분류 전부, 없으면 0) — 커뮤니티 분류 칩의 카운트. */
    @Transactional(readOnly = true)
    fun categoryCounts(): List<GatewayBoardCategoryCount> {
        val counted = postRepository.countByCategoryGrouped().associate { row ->
            (row[0] as GatewayBoardCategory) to (row[1] as Number).toLong()
        }
        return GatewayBoardCategory.entries.map { GatewayBoardCategoryCount(it, counted[it] ?: 0L) }
    }

    @Transactional
    fun detail(postId: Long, principal: BoardUserDetails?): GatewayBoardPostDetailResponse {
        val post = getPost(postId)
        // 삭제된 글은 없는 글로 취급한다. 묘비를 돌려주면 목록에서 감춘 글이 URL 로는 살아 있게 된다.
        if (post.deletedAt != null) {
            throw GatewayBoardNotFoundException()
        }
        // ADR-LITE-049 13 — 조회수. 원자적 UPDATE(동시 조회에 안전) 뒤 갱신된 값을 응답에 싣는다.
        postRepository.incrementViewCount(postId)
        post.viewCount += 1
        val commentRows = commentRepository.findByPostIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(postId)
        val authors = authorsOf(commentRows.map { it.authorAccountId } + post.authorAccountId)
        val comments = commentRows.map { commentResponse(it, principal, authors) }
        return GatewayBoardPostDetailResponse(postResponse(post, principal, authors, commentRows.size.toLong()), comments)
    }

    // ── ADR-LITE-049 13 — 신고 ──────────────────────────────────────────────

    @Transactional
    fun reportPost(postId: Long, request: CreateGatewayBoardReportRequest, principal: BoardUserDetails): GatewayBoardReportResponse {
        val post = getPost(postId)
        if (post.deletedAt != null) throw GatewayBoardNotFoundException()
        if (reportRepository.existsByPostIdAndReporterAccountIdAndStatus(postId, principal.id, GatewayBoardReportStatus.OPEN)) {
            throw GatewayBoardConflictException("이미 신고한 게시글입니다.")
        }
        val saved = reportRepository.save(
            GatewayBoardReportEntity(postId = postId, commentId = null, reporterAccountId = principal.id, reason = request.reason.trim()),
        )
        return reportResponse(saved, mapOf(principal.id to AuthorView(principal.nickname, null, 0, null, null)), targetSummariesOf(listOf(saved)))
    }

    @Transactional
    fun reportComment(postId: Long, commentId: Long, request: CreateGatewayBoardReportRequest, principal: BoardUserDetails): GatewayBoardReportResponse {
        val comment = commentRepository.findById(commentId).orElseThrow { GatewayBoardNotFoundException() }
        if (comment.postId != postId || comment.deletedAt != null) throw GatewayBoardNotFoundException()
        if (reportRepository.existsByCommentIdAndReporterAccountIdAndStatus(commentId, principal.id, GatewayBoardReportStatus.OPEN)) {
            throw GatewayBoardConflictException("이미 신고한 댓글입니다.")
        }
        val saved = reportRepository.save(
            GatewayBoardReportEntity(postId = null, commentId = commentId, reporterAccountId = principal.id, reason = request.reason.trim()),
        )
        return reportResponse(saved, mapOf(principal.id to AuthorView(principal.nickname, null, 0, null, null)), targetSummariesOf(listOf(saved)))
    }

    /** 신고 목록 — 관리자만. status 없으면 전부(최신순). */
    @Transactional(readOnly = true)
    fun listReports(status: GatewayBoardReportStatus?, page: Int, size: Int, principal: BoardUserDetails?): List<GatewayBoardReportResponse> {
        if (principal?.isAdmin() != true) throw GatewayBoardForbiddenException("신고 목록은 관리자만 볼 수 있습니다.")
        require(page >= 0) { "page는 0 이상이어야 합니다." }
        require(size in 1..100) { "size는 1부터 100 사이여야 합니다." }
        val pageable = PageRequest.of(page, size)
        val rows = status?.let { reportRepository.findByStatusOrderByCreatedAtDescIdDesc(it, pageable) }
            ?: reportRepository.findAllByOrderByCreatedAtDescIdDesc(pageable)
        val reporters = authorsOf(rows.content.map { it.reporterAccountId })
        val summaries = targetSummariesOf(rows.content)
        return rows.content.map { reportResponse(it, reporters, summaries) }
    }

    @Transactional
    fun handleReport(reportId: Long, request: UpdateGatewayBoardReportRequest, principal: BoardUserDetails): GatewayBoardReportResponse {
        if (!principal.isAdmin()) throw GatewayBoardForbiddenException("신고 처리는 관리자만 할 수 있습니다.")
        val next = requireNotNull(request.status)
        require(next != GatewayBoardReportStatus.OPEN) { "처리 상태는 HANDLED 또는 DISMISSED 여야 합니다." }
        val report = reportRepository.findById(reportId).orElseThrow { GatewayBoardNotFoundException() }
        report.status = next
        report.handledByAccountId = principal.id
        report.handledAt = Instant.now()
        val saved = reportRepository.save(report)
        return reportResponse(saved, authorsOf(listOf(saved.reporterAccountId)), targetSummariesOf(listOf(saved)))
    }

    fun openReportCount(): Long = reportRepository.countByStatus(GatewayBoardReportStatus.OPEN)

    /** 신고 대상 요약(글 제목 / 댓글 80자) — 한 페이지를 글·댓글 각 한 번의 `findAllById` 로 끌어온다(PR 비평 S8, N+1 제거). 키 = 신고 id. */
    private fun targetSummariesOf(reports: Collection<GatewayBoardReportEntity>): Map<Long, String?> {
        if (reports.isEmpty()) return emptyMap()
        val posts = postRepository.findAllById(reports.mapNotNull { it.postId }.distinct()).associateBy { requireNotNull(it.id) }
        val comments = commentRepository.findAllById(reports.mapNotNull { it.commentId }.distinct()).associateBy { requireNotNull(it.id) }
        return reports.associate { report ->
            requireNotNull(report.id) to (
                report.postId?.let { posts[it]?.title }
                    ?: report.commentId?.let { comments[it]?.contentText?.take(80) }
                )
        }
    }

    private fun reportResponse(report: GatewayBoardReportEntity, reporters: Map<Long, AuthorView>, summaries: Map<Long, String?>): GatewayBoardReportResponse {
        val summary = summaries[requireNotNull(report.id)]
        return GatewayBoardReportResponse(
            id = requireNotNull(report.id),
            postId = report.postId,
            commentId = report.commentId,
            targetSummary = summary,
            reporterName = reporters[report.reporterAccountId]?.name ?: "(탈퇴)",
            reason = report.reason,
            status = report.status,
            createdAt = report.createdAt,
            handledAt = report.handledAt,
        )
    }

    /** 한 페이지의 댓글 수(GROUP BY 한 번). */
    private fun commentCountsOf(postIds: Collection<Long>): Map<Long, Long> {
        if (postIds.isEmpty()) return emptyMap()
        return commentRepository.countByPostIds(postIds).associate { row -> (row[0] as Number).toLong() to (row[1] as Number).toLong() }
    }

    @Transactional
    fun createPost(request: CreateGatewayBoardPostRequest, principal: BoardUserDetails): GatewayBoardPostResponse {
        val category = requireNotNull(request.category) { "category는 필수입니다." }
        if (category == GatewayBoardCategory.NOTICE && !principal.isAdmin()) {
            throw GatewayBoardForbiddenException("공지글은 관리자만 작성할 수 있습니다.")
        }
        val now = Instant.now()
        val saved = postRepository.save(
            GatewayBoardPostEntity(
                category = category,
                authorAccountId = principal.id,
                authorName = principal.nickname,
                title = request.title.trim(),
                contentHtml = contentSanitizer.toSafeHtml(request.content, request.contentFormat.orPlainText()),
                createdAt = now,
                updatedAt = now,
            ),
        )
        return postResponse(saved, principal, authorsOf(listOf(principal.id)))
    }

    @Transactional
    fun updatePost(
        postId: Long,
        request: UpdateGatewayBoardPostRequest,
        principal: BoardUserDetails,
    ): GatewayBoardPostResponse {
        val post = getPost(postId)
        requireOwnerOrAdmin(post.authorAccountId, principal)
        if (post.deletedAt != null) {
            throw GatewayBoardConflictException("삭제된 게시글은 수정할 수 없습니다.")
        }
        val category = requireNotNull(request.category) { "category는 필수입니다." }
        if (category == GatewayBoardCategory.NOTICE && !principal.isAdmin()) {
            throw GatewayBoardForbiddenException("공지글은 관리자만 작성할 수 있습니다.")
        }
        post.category = category
        post.title = request.title.trim()
        post.contentHtml = contentSanitizer.toSafeHtml(request.content, request.contentFormat.orPlainText())
        post.updatedAt = Instant.now()
        return postResponse(post, principal, authorsOf(listOf(post.authorAccountId)))
    }

    @Transactional
    fun createComment(
        postId: Long,
        request: CreateGatewayBoardCommentRequest,
        principal: BoardUserDetails,
    ): GatewayBoardCommentResponse {
        val post = getPost(postId)
        if (post.deletedAt != null) {
            // 존재를 흘리지 않는다 — 읽기 경로와 같은 답(없는 글)을 준다.
            throw GatewayBoardNotFoundException()
        }
        return commentResponse(
            commentRepository.save(
                GatewayBoardCommentEntity(
                    postId = postId,
                    authorAccountId = principal.id,
                    authorName = principal.nickname,
                    contentText = request.content,
                ),
            ),
            principal,
            authorsOf(listOf(principal.id)),
        )
    }

    @Transactional
    fun deletePost(postId: Long, principal: BoardUserDetails) {
        val post = getPost(postId)
        requireOwnerOrAdmin(post.authorAccountId, principal)
        if (post.deletedAt == null) {
            val now = Instant.now()
            post.deletedAt = now
            post.deletedByAccountId = principal.id
            post.pinned = false
            post.pinnedAt = null
            post.updatedAt = now
        }
    }

    @Transactional
    fun deleteComment(postId: Long, commentId: Long, principal: BoardUserDetails) {
        getPost(postId)
        val comment = commentRepository.findById(commentId).orElseThrow { GatewayBoardNotFoundException() }
        if (comment.postId != postId) {
            throw GatewayBoardNotFoundException()
        }
        requireOwnerOrAdmin(comment.authorAccountId, principal)
        if (comment.deletedAt == null) {
            comment.deletedAt = Instant.now()
            comment.deletedByAccountId = principal.id
        }
    }

    @Transactional
    fun updatePin(postId: Long, request: UpdateGatewayBoardPinRequest, principal: BoardUserDetails): GatewayBoardPostResponse {
        if (!principal.isAdmin()) {
            throw GatewayBoardForbiddenException("게시글 고정은 관리자만 변경할 수 있습니다.")
        }
        val post = getPost(postId)
        if (post.deletedAt != null) {
            throw GatewayBoardConflictException("삭제된 게시글은 고정할 수 없습니다.")
        }
        val pinned = requireNotNull(request.pinned) { "pinned는 필수입니다." }
        val now = Instant.now()
        post.pinned = pinned
        post.pinnedAt = if (pinned) now else null
        post.updatedAt = now
        return postResponse(post, principal, authorsOf(listOf(post.authorAccountId)))
    }

    private fun getPost(postId: Long): GatewayBoardPostEntity =
        postRepository.findById(postId).orElseThrow { GatewayBoardNotFoundException() }

    private fun requireOwnerOrAdmin(authorAccountId: Long?, principal: BoardUserDetails) {
        if (authorAccountId != principal.id && !principal.isAdmin()) {
            throw GatewayBoardForbiddenException("작성자 또는 관리자만 변경할 수 있습니다.")
        }
    }

    private fun BoardUserDetails.isAdmin(): Boolean =
        authorities.any { it.authority == "ROLE_ADMIN" }

    private fun GatewayBoardContentFormat?.orPlainText(): GatewayBoardContentFormat =
        this ?: GatewayBoardContentFormat.PLAIN_TEXT

    private fun canDelete(authorAccountId: Long?, principal: BoardUserDetails?): Boolean =
        principal != null && (authorAccountId == principal.id || principal.isAdmin())

    /**
     * 작성자의 **현재** 닉네임·전콘. 글에 박힌 `authorName` 은 작성 시점 스냅샷이라, 닉네임을
     * 바꾸면 아이콘만 최신이고 이름은 옛것인 어긋남이 생긴다 — 둘 다 읽는 시점에 해석한다.
     * 계정이 사라졌으면 스냅샷 이름으로 떨어진다.
     */
    private data class AuthorView(
        val name: String?,
        val picture: String?,
        val imageServer: Int,
        /** ADR-LITE-049 13 — 계정 대표 장수(서버 배지). 설정 전이면 null. */
        val generalName: String?,
        val worldId: Int?,
    )

    /** 한 페이지의 작성자를 한 번의 쿼리로 끌어온다(행마다 조회하면 N+1). */
    private fun authorsOf(accountIds: Collection<Long?>): Map<Long, AuthorView> {
        val ids = accountIds.filterNotNull().toSet()
        if (ids.isEmpty()) return emptyMap()
        return userRepository.findAllById(ids).associate { user ->
            user.id to AuthorView(
                name = user.nickname?.takeIf { it.isNotBlank() },
                picture = user.picture,
                imageServer = if (user.imgsvr) 1 else 0,
                generalName = user.representativeGeneralName,
                worldId = user.representativeWorldId,
            )
        }
    }

    private fun postResponse(
        post: GatewayBoardPostEntity,
        principal: BoardUserDetails?,
        authors: Map<Long, AuthorView>,
        commentCount: Long = 0,
    ): GatewayBoardPostResponse {
        // 삭제된 글은 공개 읽기 경로에 오지 않는다(피드는 쿼리에서 걸러지고 상세는 404).
        // 여기 오는 삭제분은 어드민 감사 목록뿐이라 제목·작성자를 가리지 않는다 — 가리면
        // 무엇을 지웠는지 못 보고 조치를 할 수 없다.
        val author = post.authorAccountId?.let { authors[it] }
        return GatewayBoardPostResponse(
            id = requireNotNull(post.id),
            category = post.category,
            authorName = author?.name ?: post.authorName,
            authorPicture = author?.picture,
            authorImageServer = author?.imageServer ?: 0,
            title = post.title,
            contentHtml = post.contentHtml,
            pinned = post.deletedAt == null && post.pinned,
            canDelete = canDelete(post.authorAccountId, principal),
            deleted = post.deletedAt != null,
            createdAt = post.createdAt,
            updatedAt = post.updatedAt,
            viewCount = post.viewCount,
            commentCount = commentCount,
            authorGeneralName = author?.generalName,
            authorWorldId = author?.worldId,
        )
    }

    private fun commentResponse(
        comment: GatewayBoardCommentEntity,
        principal: BoardUserDetails?,
        authors: Map<Long, AuthorView>,
    ): GatewayBoardCommentResponse {
        // 삭제된 댓글은 읽기 경로에 오지 않는다(쿼리에서 걸러진다) — 묘비 문구가 없는 이유다.
        val author = comment.authorAccountId?.let { authors[it] }
        return GatewayBoardCommentResponse(
            id = requireNotNull(comment.id),
            authorName = author?.name ?: comment.authorName,
            authorPicture = author?.picture,
            authorImageServer = author?.imageServer ?: 0,
            content = comment.contentText,
            canDelete = canDelete(comment.authorAccountId, principal),
            deleted = false,
            createdAt = comment.createdAt,
        )
    }

    private companion object {
        /** 「인기」 창 — 최근 7일(아트보드 13 정의, 코드 상수). */
        val POPULAR_WINDOW: java.time.Duration = java.time.Duration.ofDays(7)
        val FEED_SORT: Sort = Sort.by(
            Sort.Order.desc("pinned"),
            Sort.Order.desc("pinnedAt"),
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id"),
        )
    }
}
