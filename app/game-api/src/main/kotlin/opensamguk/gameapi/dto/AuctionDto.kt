package opensamguk.gameapi.dto

import java.time.Instant

/** 경매 목록/상세 응답 */
data class AuctionResponse(
    val id: Int,
    val type: String,
    val finished: Boolean,
    val target: String?,
    val hostGeneralId: Int,
    val reqResource: String,
    val openDate: Instant,
    val closeDate: Instant,
    val detail: String,
)

/** 경매 입찰 응답 */
data class AuctionBidResponse(
    val no: Int?,
    val auctionId: Int,
    val generalId: Int,
    val owner: Int?,
    val amount: Int,
    val date: Instant,
    val aux: String,
)
