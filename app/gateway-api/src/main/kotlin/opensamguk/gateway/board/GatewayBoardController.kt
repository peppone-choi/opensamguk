package opensamguk.gateway.board

import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import opensamguk.gateway.security.CustomUserDetails
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
        @AuthenticationPrincipal principal: CustomUserDetails?,
        response: HttpServletResponse,
    ): GatewayBoardPageResponse {
        response.addHeader(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
        return boardService.list(category, page, size, principal, includeDeleted)
    }

    @GetMapping("/posts/{postId}")
    fun getPost(
        @PathVariable postId: Long,
        @AuthenticationPrincipal principal: CustomUserDetails?,
        response: HttpServletResponse,
    ): GatewayBoardPostDetailResponse {
        response.addHeader(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
        return boardService.detail(postId, principal)
    }

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPost(
        @Valid @RequestBody request: CreateGatewayBoardPostRequest,
        @AuthenticationPrincipal principal: CustomUserDetails,
    ): GatewayBoardPostResponse = boardService.createPost(request, principal)

    @PatchMapping("/posts/{postId}")
    fun updatePost(
        @PathVariable postId: Long,
        @Valid @RequestBody request: UpdateGatewayBoardPostRequest,
        @AuthenticationPrincipal principal: CustomUserDetails,
    ): GatewayBoardPostResponse = boardService.updatePost(postId, request, principal)

    @PostMapping("/posts/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    fun createComment(
        @PathVariable postId: Long,
        @Valid @RequestBody request: CreateGatewayBoardCommentRequest,
        @AuthenticationPrincipal principal: CustomUserDetails,
    ): GatewayBoardCommentResponse = boardService.createComment(postId, request, principal)

    @DeleteMapping("/posts/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePost(
        @PathVariable postId: Long,
        @AuthenticationPrincipal principal: CustomUserDetails,
    ) {
        boardService.deletePost(postId, principal)
    }

    @DeleteMapping("/posts/{postId}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteComment(
        @PathVariable postId: Long,
        @PathVariable commentId: Long,
        @AuthenticationPrincipal principal: CustomUserDetails,
    ) {
        boardService.deleteComment(postId, commentId, principal)
    }

    @PatchMapping("/posts/{postId}/pin")
    fun updatePin(
        @PathVariable postId: Long,
        @Valid @RequestBody request: UpdateGatewayBoardPinRequest,
        @AuthenticationPrincipal principal: CustomUserDetails,
    ): GatewayBoardPostResponse = boardService.updatePin(postId, request, principal)
}
