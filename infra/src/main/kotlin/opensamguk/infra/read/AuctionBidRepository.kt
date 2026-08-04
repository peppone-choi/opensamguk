package opensamguk.infra.read

import opensamguk.common.world.WorldId
import opensamguk.infra.entity.AuctionBidEntity
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.repository.Repository as SpringDataRepository

/**
 * JPA read repository for `ng_auction_bid` table.
 *
 * P7 read API uses this for bid history / highest-bid lookups. The write path
 * (daemon flush) goes through [AuctionBidRowMapper] + [JdbcFlushExecutor].
 */
interface AuctionBidRepository {
    fun findByAuctionIdOrderByAmountDesc(auctionId: Int): List<AuctionBidEntity>
    fun findByAuctionId(auctionId: Int): List<AuctionBidEntity>
    fun findTopByAuctionIdOrderByAmountDesc(auctionId: Int): AuctionBidEntity?
    fun findHighestBidsByAuctionIds(auctionIds: List<Int>): List<AuctionBidEntity>
}

internal interface AuctionBidRawRepository : SpringDataRepository<AuctionBidEntity, Int> {
    @Query(
        value = "SELECT * FROM ng_auction_bid WHERE world_id = :worldId AND auction_id = :auctionId ORDER BY amount DESC",
        nativeQuery = true,
    )
    fun findByWorldIdAndAuctionIdOrderByAmountDesc(
        @Param("worldId") worldId: Int,
        @Param("auctionId") auctionId: Int,
    ): List<AuctionBidEntity>

    @Query(
        value = "SELECT * FROM ng_auction_bid WHERE world_id = :worldId AND auction_id = :auctionId",
        nativeQuery = true,
    )
    fun findByWorldIdAndAuctionId(
        @Param("worldId") worldId: Int,
        @Param("auctionId") auctionId: Int,
    ): List<AuctionBidEntity>

    @Query(
        value = "SELECT * FROM ng_auction_bid WHERE world_id = :worldId AND auction_id = :auctionId ORDER BY amount DESC LIMIT 1",
        nativeQuery = true,
    )
    fun findTopByWorldIdAndAuctionIdOrderByAmountDesc(
        @Param("worldId") worldId: Int,
        @Param("auctionId") auctionId: Int,
    ): AuctionBidEntity?

    @Query(
        value = """
            SELECT b.* FROM ng_auction_bid b
            WHERE b.world_id = :worldId
              AND b.auction_id IN (:auctionIds)
              AND b.amount = (
                  SELECT MAX(b2.amount) FROM ng_auction_bid b2
                  WHERE b2.world_id = :worldId
                    AND b2.auction_id = b.auction_id
              )
        """,
        nativeQuery = true,
    )
    fun findHighestBidsByAuctionIds(
        @Param("worldId") worldId: Int,
        @Param("auctionIds") auctionIds: List<Int>,
    ): List<AuctionBidEntity>
}

internal class WorldScopedAuctionBidRepository(
    private val raw: AuctionBidRawRepository,
    private val worldId: WorldId,
) : AuctionBidRepository {
    override fun findByAuctionIdOrderByAmountDesc(auctionId: Int): List<AuctionBidEntity> =
        raw.findByWorldIdAndAuctionIdOrderByAmountDesc(worldId.value, auctionId)

    override fun findByAuctionId(auctionId: Int): List<AuctionBidEntity> =
        raw.findByWorldIdAndAuctionId(worldId.value, auctionId)

    override fun findTopByAuctionIdOrderByAmountDesc(auctionId: Int): AuctionBidEntity? =
        raw.findTopByWorldIdAndAuctionIdOrderByAmountDesc(worldId.value, auctionId)

    override fun findHighestBidsByAuctionIds(auctionIds: List<Int>): List<AuctionBidEntity> =
        if (auctionIds.isEmpty()) emptyList() else raw.findHighestBidsByAuctionIds(worldId.value, auctionIds)
}
