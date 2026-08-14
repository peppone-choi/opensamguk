package opensamguk.gateway.board

import opensamguk.gateway.security.CustomUserDetails
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
) {

    @Transactional(readOnly = true)
    fun list(
        category: GatewayBoardCategory?,
        page: Int,
        size: Int,
        principal: CustomUserDetails?,
    ): GatewayBoardPageResponse {
        require(page >= 0) { "page는 0 이상이어야 합니다." }
        require(size in 1..50) { "size는 1부터 50 사이여야 합니다." }
        val pageable = PageRequest.of(page, size, FEED_SORT)
        val result = category?.let { postRepository.findByCategory(it, pageable) }
            ?: postRepository.findAll(pageable)
        return GatewayBoardPageResponse(
            content = result.content.map { postResponse(it, principal) },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun detail(postId: Long, principal: CustomUserDetails?): GatewayBoardPostDetailResponse {
        val post = getPost(postId)
        val comments = if (post.deletedAt == null) {
            commentRepository.findByPostIdOrderByCreatedAtAscIdAsc(postId).map { commentResponse(it, principal) }
        } else {
            emptyList()
        }
        return GatewayBoardPostDetailResponse(postResponse(post, principal), comments)
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
                authorName = principal.username,
                title = request.title.trim(),
                contentHtml = contentSanitizer.toSafeHtml(request.content, request.contentFormat.orPlainText()),
                createdAt = now,
                updatedAt = now,
            ),
        )
        return postResponse(saved, principal)
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
        return postResponse(post, principal)
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
                    authorName = principal.username,
                    contentText = request.content,
                ),
            ),
            principal,
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
        return postResponse(post, principal)
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

    private fun postResponse(
        post: GatewayBoardPostEntity,
        principal: CustomUserDetails?,
    ): GatewayBoardPostResponse {
        val deleted = post.deletedAt != null
        return GatewayBoardPostResponse(
            id = requireNotNull(post.id),
            category = post.category,
            authorName = if (deleted) DELETED_AUTHOR_NAME else post.authorName,
            title = if (deleted) DELETED_POST_TEXT else post.title,
            contentHtml = if (deleted) DELETED_POST_TEXT else post.contentHtml,
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
    ): GatewayBoardCommentResponse {
        val deleted = comment.deletedAt != null
        return GatewayBoardCommentResponse(
            id = requireNotNull(comment.id),
            authorName = if (deleted) DELETED_AUTHOR_NAME else comment.authorName,
            content = if (deleted) DELETED_COMMENT_TEXT else comment.contentText,
            canDelete = canDelete(comment.authorAccountId, principal),
            deleted = deleted,
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
        const val DELETED_COMMENT_TEXT = "삭제된 댓글입니다."
    }
}
