package opensamguk.infra.read

import opensamguk.infra.entity.AuctionEntity
import opensamguk.logic.auction.AuctionType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * JPA read repository for `ng_auction` table.
 *
 * P7 read API uses this for auction listing / detail lookups. The write path
 * (daemon flush) goes through [AuctionRowMapper] + [JdbcFlushExecutor] — no
 * `save()`/`delete()` calls from the engine.
 */
@Repository
interface AuctionRepository : JpaRepository<AuctionEntity, Int> {

    /** Active auctions: `finished = false`. */
    fun findByFinishedFalse(): List<AuctionEntity>

    /** Find auctions by type (e.g. `buyRice`, `sellRice`, `uniqueItem`). */
    fun findByType(type: AuctionType): List<AuctionEntity>

    /** Active auctions by type. */
    fun findByFinishedFalseAndType(type: AuctionType): List<AuctionEntity>
}
