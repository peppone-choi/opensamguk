package opensamguk.boardapi.board

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

/** 기존 JSON category 문자열을 유지하는 동적 게시판 키. 존재/쓰기 권한은 DB 정의가 판정한다. */
data class GatewayBoardCategory @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING) constructor(
    @get:com.fasterxml.jackson.annotation.JsonValue val name: String,
) {
    init { require(name.matches(Regex("[A-Z][A-Z0-9_]{0,31}"))) { "게시판 키가 올바르지 않습니다." } }

    companion object {
        val NOTICE = GatewayBoardCategory("NOTICE")
        val FREE = GatewayBoardCategory("FREE")
        val SUGGESTION = GatewayBoardCategory("SUGGESTION")
        val STRATEGY = GatewayBoardCategory("STRATEGY")
        val SERVER = GatewayBoardCategory("SERVER")
        val CREATIVE = GatewayBoardCategory("CREATIVE")
        @JvmStatic fun valueOf(value: String) = GatewayBoardCategory(value)
    }
}

/** 목록 정렬 — latest(고정 글 우선·최신), popular(최근 7일 조회+댓글×5 가중), mine(내 글). */
enum class GatewayBoardSort {
    LATEST,
    POPULAR,
    MINE,
}

enum class GatewayBoardReportStatus {
    OPEN,
    HANDLED,
    DISMISSED,
}

data class CreateGatewayBoardReportRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val reason: String,
)

data class UpdateGatewayBoardReportRequest(
    @field:NotNull
    val status: GatewayBoardReportStatus?,
)

data class GatewayBoardReportResponse(
    val id: Long,
    val postId: Long?,
    val commentId: Long?,
    /** 신고 대상 요약 — 글 제목 또는 댓글 앞 80자. 대상이 지워졌으면 null. */
    val targetSummary: String?,
    val reporterName: String,
    val reason: String,
    val status: GatewayBoardReportStatus,
    val createdAt: Instant,
    val handledAt: Instant?,
)

data class GatewayBoardCategoryCount(
    val category: GatewayBoardCategory,
    val count: Long,
    val boardId: Long,
    val key: String,
    val name: String,
    val sortOrder: Int,
    val writable: Boolean,
    val createdAt: Instant,
)

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
    // ADR-LITE-049 13 — 조회수·댓글 수·작성자 대표 장수(서버 배지). 원천이 없으면 0/null.
    val viewCount: Int = 0,
    val commentCount: Long = 0,
    val authorGeneralName: String? = null,
    val authorWorldId: Int? = null,
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


data class CreateGatewayBoardDefinitionRequest(
    @field:NotBlank @field:Size(max = 32) val key: String,
    @field:NotBlank @field:Size(max = 80) val name: String,
    val sortOrder: Int = 0,
    val writable: Boolean = true,
)

data class UpdateGatewayBoardDefinitionRequest(
    @field:Size(min = 1, max = 80) val name: String? = null,
    val sortOrder: Int? = null,
    val writable: Boolean? = null,
)

data class GatewayBoardDefinitionResponse(
    val boardId: Long, val key: String, val name: String, val sortOrder: Int,
    val writable: Boolean, val createdAt: Instant,
)
