package opensamguk.gateway.board

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

enum class GatewayBoardCategory {
    NOTICE,
    FREE,
    SUGGESTION,
}

enum class GatewayBoardContentFormat {
    PLAIN_TEXT,
    RICH_HTML,
}

data class CreateGatewayBoardPostRequest(
    @field:NotNull
    val category: GatewayBoardCategory?,
    @field:NotBlank
    @field:Size(max = 120)
    val title: String,
    @field:NotBlank
    @field:Size(max = 10_000)
    val content: String,
    val contentFormat: GatewayBoardContentFormat? = null,
)

data class UpdateGatewayBoardPostRequest(
    @field:NotNull
    val category: GatewayBoardCategory?,
    @field:NotBlank
    @field:Size(max = 120)
    val title: String,
    @field:NotBlank
    @field:Size(max = 10_000)
    val content: String,
    val contentFormat: GatewayBoardContentFormat? = null,
)

data class CreateGatewayBoardCommentRequest(
    @field:NotBlank
    @field:Size(max = 2_000)
    val content: String,
)

data class UpdateGatewayBoardPinRequest(
    @field:NotNull
    val pinned: Boolean?,
)

data class GatewayBoardPostResponse(
    val id: Long,
    val category: GatewayBoardCategory,
    val authorName: String,
    // 전콘(프로필 아이콘) 원본값. URL 은 프런트 portraitUrl() 이 만든다 — 서버가 만들면
    // CDN/`/d_pic` 경로 규약이 두 군데로 갈라진다.
    val authorPicture: String?,
    val authorImageServer: Int,
    val title: String,
    val contentHtml: String,
    val pinned: Boolean,
    val canDelete: Boolean,
    val deleted: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class GatewayBoardCommentResponse(
    val id: Long,
    val authorName: String,
    val authorPicture: String?,
    val authorImageServer: Int,
    val content: String,
    val canDelete: Boolean,
    val deleted: Boolean,
    val createdAt: Instant,
)

data class GatewayBoardPostDetailResponse(
    val post: GatewayBoardPostResponse,
    val comments: List<GatewayBoardCommentResponse>,
)

data class GatewayBoardPageResponse(
    val content: List<GatewayBoardPostResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)
