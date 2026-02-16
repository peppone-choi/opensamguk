package com.opensam.controller

import com.opensam.dto.CastVoteRequest
import com.opensam.dto.CreateVoteRequest
import com.opensam.dto.MessageResponse
import com.opensam.service.VoteService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class VoteController(
    private val voteService: VoteService,
) {
    @GetMapping("/worlds/{worldId}/votes")
    fun listVotes(@PathVariable worldId: Long): ResponseEntity<List<MessageResponse>> {
        return ResponseEntity.ok(voteService.listVotes(worldId).map { MessageResponse.from(it) })
    }

    @PostMapping("/worlds/{worldId}/votes")
    fun createVote(
        @PathVariable worldId: Long,
        @RequestBody request: CreateVoteRequest,
    ): ResponseEntity<MessageResponse> {
        val vote = voteService.createVote(worldId, request.creatorId, request.title, request.options)
        return ResponseEntity.status(HttpStatus.CREATED).body(MessageResponse.from(vote))
    }

    @PostMapping("/votes/{id}/cast")
    fun castVote(
        @PathVariable id: Long,
        @RequestBody request: CastVoteRequest,
    ): ResponseEntity<Void> {
        if (!voteService.castVote(id, request.voterId, request.optionIndex)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }

    @PostMapping("/votes/{id}/close")
    fun closeVote(@PathVariable id: Long): ResponseEntity<Void> {
        if (!voteService.closeVote(id)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }
}
