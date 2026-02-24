package com.opensam.dto

data class CreateAuctionRequest(
    val type: String,
    val sellerId: Long,
    val item: String,
    val amount: Int,
    val minPrice: Int,
    val finishBidAmount: Int? = null,
    val closeTurnCnt: Int? = null,
)

data class BidRequest(val bidderId: Long, val amount: Int)

data class MarketTradeRequest(
    val generalId: Long,
    val amount: Int,
)

data class CreateItemAuctionRequest(
    val generalId: Long,
    val itemType: String,
    val startPrice: Int,
)

data class CancelAuctionRequest(
    val generalId: Long,
)
