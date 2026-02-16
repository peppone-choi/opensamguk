package com.opensam.dto

data class ContactInfo(
    val generalId: Long,
    val name: String,
    val nationId: Long,
    val nationName: String,
    val picture: String,
)

data class SendMessageRequest(
    val worldId: Long,
    val mailboxCode: String,
    val messageType: String,
    val srcId: Long? = null,
    val destId: Long? = null,
    val payload: Map<String, Any> = emptyMap(),
)

data class DiplomacyRespondRequest(
    val accept: Boolean,
)
