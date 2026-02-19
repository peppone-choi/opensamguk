package com.opensam.dto

import java.time.OffsetDateTime

data class CreateVoteRequest(val creatorId: Long, val title: String, val options: List<String>)

data class CastVoteRequest(val voterId: Long, val optionIndex: Int)

data class CreateVoteCommentRequest(val authorGeneralId: Long, val content: String)

data class VoteCommentResponse(
    val id: Long,
    val authorGeneralId: Long,
    val content: String,
    val createdAt: OffsetDateTime,
)
