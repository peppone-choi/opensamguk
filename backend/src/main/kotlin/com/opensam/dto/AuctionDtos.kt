package com.opensam.dto

data class CreateAuctionRequest(
    val type: String,
    val sellerId: Long,
    val item: String,
    val amount: Int,
    val minPrice: Int,
)

data class BidRequest(val bidderId: Long, val amount: Int)
