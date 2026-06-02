package opensamguk.gameapi.dto

/** 베팅 참여 응답 */
data class BettingItemResponse(
    val id: Int?,
    val bettingId: Int,
    val generalId: Int,
    val userId: Int?,
    val bettingType: String,
    val amount: Int,
)
