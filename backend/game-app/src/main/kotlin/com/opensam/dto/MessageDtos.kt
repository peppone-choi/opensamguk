package com.opensam.dto

import java.time.OffsetDateTime

data class ContactInfo(
    val generalId: Long,
    val name: String,
    val nationId: Long,
    val nationName: String,
    val nationColor: String? = null,
    val picture: String,
)

data class SendMessageRequest(
    val worldId: Long,
    val mailboxCode: String,
    val mailboxType: String? = null,
    val messageType: String,
    val srcId: Long? = null,
    val destId: Long? = null,
    val officerLevel: Short? = null,
    val payload: Map<String, Any> = emptyMap(),
)

data class DiplomacyRespondRequest(
    val accept: Boolean,
)

data class CreateBoardCommentRequest(
    val authorGeneralId: Long,
    val content: String,
)

data class BoardCommentResponse(
    val id: Long,
    val authorGeneralId: Long,
    val content: String,
    val createdAt: OffsetDateTime,
)
