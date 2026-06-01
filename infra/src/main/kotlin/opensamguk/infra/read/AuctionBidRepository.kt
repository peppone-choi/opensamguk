package opensamguk.infra.read

import opensamguk.infra.entity.AuctionBidEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * JPA read repository for `ng_auction_bid` table.
 *
 * P7 read API uses this for bid history / highest-bid lookups. The write path
 * (daemon flush) goes through [AuctionBidRowMapper] + [JdbcFlushExecutor].
 */
@Repository
interface AuctionBidRepository : JpaRepository<AuctionBidEntity, Int> {

    /** All bids for a given auction, ordered by amount descending (highest first). */
    fun findByAuctionIdOrderByAmountDesc(auctionId: Int): List<AuctionBidEntity>

    /** Convenience: all bids for an auction (any order). */
    fun findByAuctionId(auctionId: Int): List<AuctionBidEntity>

    /** The single highest bid for an auction. */
    fun findTopByAuctionIdOrderByAmountDesc(auctionId: Int): AuctionBidEntity?
}
