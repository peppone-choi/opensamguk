package opensamguk.boardapi.board

import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import opensamguk.boardapi.security.BoardUserDetails
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/board")
class GatewayBoardController(
    private val boardService: GatewayBoardService,
) {

    @GetMapping("/posts")
    fun listPosts(
        @RequestParam(required = false) category: GatewayBoardCategory?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "false") includeDeleted: Boolean,
        // ADR-LITE-049 13 — latest | popular | mine, 검색어 q(제목·본문 ILIKE)
        @RequestParam(defaultValue = "latest") sort: String,
        @RequestParam(required = false) q: String?,
        @AuthenticationPrincipal principal: BoardUserDetails?,
        response: HttpServletResponse,
    ): GatewayBoardPageResponse {
        response.addHeader(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
        val sortValue = runCatching { GatewayBoardSort.valueOf(sort.uppercase()) }.getOrElse {
            throw IllegalArgumentException("sort 는 latest, popular, mine 중 하나여야 합니다.")
        }
        return boardService.list(category, page, size, principal, includeDeleted, sortValue, q)
    }

    /** 분류별 공개 글 수(6 분류) — 커뮤니티 분류 칩. */
    @GetMapping("/categories")
    fun categories(): List<GatewayBoardCategoryCount> = boardService.categoryCounts()

    // ── ADR-LITE-049 13 — 신고 ──────────────────────────────────────────────
    @PostMapping("/posts/{postId}/report")
    @ResponseStatus(HttpStatus.CREATED)
    fun reportPost(
        @PathVariable postId: Long,
        @Valid @RequestBody request: CreateGatewayBoardReportRequest,
        @AuthenticationPrincipal principal: BoardUserDetails,
    ): GatewayBoardReportResponse = boardService.reportPost(postId, request, principal)

    @PostMapping("/posts/{postId}/comments/{commentId}/report")
    @ResponseStatus(HttpStatus.CREATED)
    fun reportComment(
        @PathVariable postId: Long,
        @PathVariable commentId: Long,
        @Valid @RequestBody request: CreateGatewayBoardReportRequest,
        @AuthenticationPrincipal principal: BoardUserDetails,
    ): GatewayBoardReportResponse = boardService.reportComment(postId, commentId, request, principal)

    /** 관리자 — 신고 목록(status 없으면 전부). 비관리자는 403. */
    @GetMapping("/admin/reports")
    fun listReports(
        @RequestParam(required = false) status: GatewayBoardReportStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @AuthenticationPrincipal principal: BoardUserDetails?,
        response: HttpServletResponse,
    ): List<GatewayBoardReportResponse> {
        response.addHeader(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
        return boardService.listReports(status, page, size, principal)
    }

    @PatchMapping("/admin/reports/{reportId}")
    fun handleReport(
        @PathVariable reportId: Long,
        @Valid @RequestBody request: UpdateGatewayBoardReportRequest,
        @AuthenticationPrincipal principal: BoardUserDetails,
    ): GatewayBoardReportResponse = boardService.handleReport(reportId, request, principal)

    @GetMapping("/posts/{postId}")
    fun getPost(
        @PathVariable postId: Long,
        @AuthenticationPrincipal principal: BoardUserDetails?,
        response: HttpServletResponse,
    ): GatewayBoardPostDetailResponse {
        response.addHeader(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
        return boardService.detail(postId, principal)
    }

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPost(
        @Valid @RequestBody request: CreateGatewayBoardPostRequest,
        @AuthenticationPrincipal principal: BoardUserDetails,
    ): GatewayBoardPostResponse = boardService.createPost(request, principal)

    @PatchMapping("/posts/{postId}")
    fun updatePost(
        @PathVariable postId: Long,
        @Valid @RequestBody request: UpdateGatewayBoardPostRequest,
        @AuthenticationPrincipal principal: BoardUserDetails,
    ): GatewayBoardPostResponse = boardService.updatePost(postId, request, principal)

    @PostMapping("/posts/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    fun createComment(
        @PathVariable postId: Long,
        @Valid @RequestBody request: CreateGatewayBoardCommentRequest,
        @AuthenticationPrincipal principal: BoardUserDetails,
    ): GatewayBoardCommentResponse = boardService.createComment(postId, request, principal)

    @DeleteMapping("/posts/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePost(
        @PathVariable postId: Long,
        @AuthenticationPrincipal principal: BoardUserDetails,
    ) {
        boardService.deletePost(postId, principal)
    }

    @DeleteMapping("/posts/{postId}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteComment(
        @PathVariable postId: Long,
        @PathVariable commentId: Long,
        @AuthenticationPrincipal principal: BoardUserDetails,
    ) {
        boardService.deleteComment(postId, commentId, principal)
    }

    @PatchMapping("/posts/{postId}/pin")
    fun updatePin(
        @PathVariable postId: Long,
        @Valid @RequestBody request: UpdateGatewayBoardPinRequest,
        @AuthenticationPrincipal principal: BoardUserDetails,
    ): GatewayBoardPostResponse = boardService.updatePin(postId, request, principal)
}
