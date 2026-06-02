package opensamguk.gameapi.dto

/** 외교 메시지 수락 응답 */
data class AcceptDiplomaticMessageResponse(
    val status: String,
    val commandKey: String,
    val commandArgs: Map<String, Any?>,
)

/** 외교 메시지 거절 응답 */
data class DeclineDiplomaticMessageResponse(
    val status: String,
)
