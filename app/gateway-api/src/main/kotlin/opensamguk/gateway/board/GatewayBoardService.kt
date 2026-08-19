package opensamguk.gateway.board

import opensamguk.gateway.security.CustomUserDetails
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
) {

    @Transactional(readOnly = true)
    fun list(
        category: GatewayBoardCategory?,
        page: Int,
        size: Int,
        principal: CustomUserDetails?,
        includeDeleted: Boolean = false,
    ): GatewayBoardPageResponse {
        require(page >= 0) { "page는 0 이상이어야 합니다." }
        require(size in 1..50) { "size는 1부터 50 사이여야 합니다." }
        // 삭제분은 어드민 감사 화면에서만 보인다. 공개 피드가 이 문을 열 수 없게 권한으로 막는다.
        if (includeDeleted && principal?.isAdmin() != true) {
            throw GatewayBoardForbiddenException("삭제된 게시글은 관리자만 조회할 수 있습니다.")
        }
        val pageable = PageRequest.of(page, size, FEED_SORT)
        val result = if (includeDeleted) {
            category?.let { postRepository.findByCategory(it, pageable) } ?: postRepository.findAll(pageable)
        } else {
            category?.let { postRepository.findByCategoryAndDeletedAtIsNull(it, pageable) }
                ?: postRepository.findByDeletedAtIsNull(pageable)
        }
        val authors = authorsOf(result.content.map { it.authorAccountId })
        return GatewayBoardPageResponse(
            content = result.content.map { postResponse(it, principal, authors, reveal = includeDeleted) },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun detail(postId: Long, principal: CustomUserDetails?): GatewayBoardPostDetailResponse {
        val post = getPost(postId)
        // 삭제된 글은 없는 글로 취급한다. 묘비를 돌려주면 목록에서 감춘 글이 URL 로는 살아 있게 된다.
        if (post.deletedAt != null) {
            throw GatewayBoardNotFoundException()
        }
        val commentRows = commentRepository.findByPostIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(postId)
        val authors = authorsOf(commentRows.map { it.authorAccountId } + post.authorAccountId)
        val comments = commentRows.map { commentResponse(it, principal, authors) }
        return GatewayBoardPostDetailResponse(postResponse(post, principal, authors), comments)
    }

    @Transactional
    fun createPost(request: CreateGatewayBoardPostRequest, principal: CustomUserDetails): GatewayBoardPostResponse {
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
        principal: CustomUserDetails,
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
        return postResponse(post, principal, authorsOf(listOf(principal.id)))
    }

    @Transactional
    fun createComment(
        postId: Long,
        request: CreateGatewayBoardCommentRequest,
        principal: CustomUserDetails,
    ): GatewayBoardCommentResponse {
        val post = getPost(postId)
        if (post.deletedAt != null) {
            throw GatewayBoardConflictException("삭제된 게시글에는 댓글을 작성할 수 없습니다.")
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
    fun deletePost(postId: Long, principal: CustomUserDetails) {
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
    fun deleteComment(postId: Long, commentId: Long, principal: CustomUserDetails) {
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
    fun updatePin(postId: Long, request: UpdateGatewayBoardPinRequest, principal: CustomUserDetails): GatewayBoardPostResponse {
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
        return postResponse(post, principal, authorsOf(listOf(principal.id)))
    }

    private fun getPost(postId: Long): GatewayBoardPostEntity =
        postRepository.findById(postId).orElseThrow { GatewayBoardNotFoundException() }

    private fun requireOwnerOrAdmin(authorAccountId: Long?, principal: CustomUserDetails) {
        if (authorAccountId != principal.id && !principal.isAdmin()) {
            throw GatewayBoardForbiddenException("작성자 또는 관리자만 변경할 수 있습니다.")
        }
    }

    private fun CustomUserDetails.isAdmin(): Boolean =
        authorities.any { it.authority == "ROLE_ADMIN" }

    private fun GatewayBoardContentFormat?.orPlainText(): GatewayBoardContentFormat =
        this ?: GatewayBoardContentFormat.PLAIN_TEXT

    private fun canDelete(authorAccountId: Long?, principal: CustomUserDetails?): Boolean =
        principal != null && (authorAccountId == principal.id || principal.isAdmin())

    /**
     * 작성자의 **현재** 닉네임·전콘. 글에 박힌 `authorName` 은 작성 시점 스냅샷이라, 닉네임을
     * 바꾸면 아이콘만 최신이고 이름은 옛것인 어긋남이 생긴다 — 둘 다 읽는 시점에 해석한다.
     * 계정이 사라졌으면 스냅샷 이름으로 떨어진다.
     */
    private data class AuthorView(val name: String?, val picture: String?, val imageServer: Int)

    /** 한 페이지의 작성자를 한 번의 쿼리로 끌어온다(행마다 조회하면 N+1). */
    private fun authorsOf(accountIds: Collection<Long?>): Map<Long, AuthorView> {
        val ids = accountIds.filterNotNull().toSet()
        if (ids.isEmpty()) return emptyMap()
        return userRepository.findAllById(ids).associate { user ->
            user.id to AuthorView(
                name = user.nickname?.takeIf { it.isNotBlank() },
                picture = user.picture,
                imageServer = if (user.imgsvr) 1 else 0,
            )
        }
    }

    private fun postResponse(
        post: GatewayBoardPostEntity,
        principal: CustomUserDetails?,
        authors: Map<Long, AuthorView>,
        // 어드민 감사 목록은 삭제된 글의 제목·작성자를 그대로 봐야 조치를 할 수 있다.
        reveal: Boolean = false,
    ): GatewayBoardPostResponse {
        val deleted = post.deletedAt != null
        val mask = deleted && !reveal
        val author = post.authorAccountId?.let { authors[it] }
        return GatewayBoardPostResponse(
            id = requireNotNull(post.id),
            category = post.category,
            authorName = if (mask) DELETED_AUTHOR_NAME else author?.name ?: post.authorName,
            authorPicture = if (mask) null else author?.picture,
            authorImageServer = if (mask) 0 else author?.imageServer ?: 0,
            title = if (mask) DELETED_POST_TEXT else post.title,
            contentHtml = if (mask) DELETED_POST_TEXT else post.contentHtml,
            pinned = !deleted && post.pinned,
            canDelete = canDelete(post.authorAccountId, principal),
            deleted = deleted,
            createdAt = post.createdAt,
            updatedAt = post.updatedAt,
        )
    }

    private fun commentResponse(
        comment: GatewayBoardCommentEntity,
        principal: CustomUserDetails?,
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
        val FEED_SORT: Sort = Sort.by(
            Sort.Order.desc("pinned"),
            Sort.Order.desc("pinnedAt"),
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id"),
        )
        const val DELETED_AUTHOR_NAME = "삭제된 사용자"
        const val DELETED_POST_TEXT = "삭제된 게시글입니다."
    }
}
