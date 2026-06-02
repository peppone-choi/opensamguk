package opensamguk.gameapi.dto

import java.time.Instant

/** 메시지 응답 */
data class MessageResponse(
    val id: Int?,
    val mailbox: Int,
    val type: String,
    val src: Int,
    val dest: Int,
    val time: Instant,
    val validUntil: Instant,
    val message: String,
)
