package com.opensam.controller

import com.opensam.dto.BoardCommentResponse
import com.opensam.dto.CreateBoardCommentRequest
import com.opensam.service.MessageService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/boards")
class BoardController(
    private val messageService: MessageService,
) {
    @GetMapping("/{postId}/comments")
    fun getComments(@PathVariable postId: Long): ResponseEntity<List<BoardCommentResponse>> {
        return ResponseEntity.ok(messageService.getBoardComments(postId))
    }

    @PostMapping("/{postId}/comments")
    fun createComment(
        @PathVariable postId: Long,
        @RequestBody request: CreateBoardCommentRequest,
    ): ResponseEntity<BoardCommentResponse> {
        val created = messageService.createBoardComment(postId, request.authorGeneralId, request.content)
        return ResponseEntity.status(HttpStatus.CREATED).body(created)
    }

    @DeleteMapping("/{postId}/comments/{commentId}")
    fun deleteComment(
        @PathVariable postId: Long,
        @PathVariable commentId: Long,
        @RequestParam generalId: Long,
    ): ResponseEntity<Void> {
        if (!messageService.deleteBoardComment(postId, commentId, generalId)) {
            return ResponseEntity.notFound().build()
        }
        return ResponseEntity.noContent().build()
    }
}
