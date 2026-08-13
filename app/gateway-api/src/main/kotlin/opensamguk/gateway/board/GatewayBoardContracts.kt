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

data class CreateGatewayBoardPostRequest(
    @field:NotNull
    val category: GatewayBoardCategory?,
    @field:NotBlank
    @field:Size(max = 120)
    val title: String,
    @field:NotBlank
    @field:Size(max = 10_000)
    val content: String,
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
