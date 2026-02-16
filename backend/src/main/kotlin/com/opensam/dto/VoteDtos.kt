package com.opensam.dto

data class CreateVoteRequest(val creatorId: Long, val title: String, val options: List<String>)

data class CastVoteRequest(val voterId: Long, val optionIndex: Int)
