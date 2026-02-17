package com.opensam.repository

import com.opensam.entity.Auction
import org.springframework.data.jpa.repository.JpaRepository

interface AuctionRepository : JpaRepository<Auction, Long> {
    fun findByWorldId(worldId: Long): List<Auction>
    fun findByWorldIdAndStatus(worldId: Long, status: String): List<Auction>
    fun findBySellerGeneralId(sellerGeneralId: Long): List<Auction>
}
