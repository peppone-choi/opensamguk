package com.opensam.repository

import com.opensam.entity.Auction
import org.springframework.data.jpa.repository.JpaRepository

interface AuctionRepository : JpaRepository<Auction, Long> {
    fun findByWorldId(worldId: Long): List<Auction>
    fun findByWorldIdAndStatus(worldId: Long, status: String): List<Auction>
    fun findByWorldIdAndStatusOrderByCreatedAtDesc(worldId: Long, status: String): List<Auction>
    fun findByWorldIdAndStatusNotOrderByCreatedAtDesc(worldId: Long, status: String): List<Auction>
    fun findBySellerGeneralId(sellerGeneralId: Long): List<Auction>
    fun findByStatusAndExpiresAtLessThanEqual(status: String, expiresAt: java.time.OffsetDateTime): List<Auction>
}
