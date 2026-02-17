package com.opensam.repository

import com.opensam.entity.AuctionBid
import org.springframework.data.jpa.repository.JpaRepository

interface AuctionBidRepository : JpaRepository<AuctionBid, Long> {
    fun findByAuctionId(auctionId: Long): List<AuctionBid>
    fun findByBidderGeneralId(bidderGeneralId: Long): List<AuctionBid>
    fun findByAuctionIdOrderByAmountDesc(auctionId: Long): List<AuctionBid>
}
