package opensamguk.infra.read

import opensamguk.infra.entity.AuctionEntity
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

    @Query(
        value = """
            SELECT * FROM ng_auction
            WHERE type = CAST(:type AS ng_auction_type)
        """,
        nativeQuery = true,
    )
    fun findByTypeValue(@Param("type") type: String): List<AuctionEntity>

    @Query(
        value = """
            SELECT * FROM ng_auction
            WHERE finished = false
              AND type = CAST(:type AS ng_auction_type)
        """,
        nativeQuery = true,
    )
    fun findByFinishedFalseAndTypeValue(@Param("type") type: String): List<AuctionEntity>

    @Query(
        value = """
            SELECT * FROM ng_auction
            WHERE type = CAST(:type AS ng_auction_type)
            ORDER BY close_date ASC
        """,
        nativeQuery = true,
    )
    fun findByTypeValueOrderByCloseDateAsc(@Param("type") type: String): List<AuctionEntity>
}
