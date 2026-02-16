package com.opensam.dto

data class SendLetterRequest(val worldId: Long, val destNationId: Long, val type: String, val content: String? = null)

data class RespondLetterRequest(val accept: Boolean)
