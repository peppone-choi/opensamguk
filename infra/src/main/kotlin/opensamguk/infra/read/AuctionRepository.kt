package opensamguk.infra.read

import opensamguk.common.world.WorldId
import opensamguk.infra.entity.AuctionEntity
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional
import org.springframework.data.repository.Repository as SpringDataRepository

/**
 * JPA read repository for `ng_auction` table.
 *
 * P7 read API uses this for auction listing / detail lookups. The write path
 * (daemon flush) goes through [AuctionRowMapper] + [JdbcFlushExecutor] — no
 * `save()`/`delete()` calls from the engine.
 */
interface AuctionRepository {
    fun findById(id: Int): Optional<AuctionEntity>
    fun findByFinishedFalse(): List<AuctionEntity>
    fun findByTypeValue(type: String): List<AuctionEntity>
    fun findByFinishedFalseAndTypeValue(type: String): List<AuctionEntity>
    fun findByTypeValueOrderByCloseDateAsc(type: String): List<AuctionEntity>
    fun findMaxId(): Int
}

internal interface AuctionRawRepository : SpringDataRepository<AuctionEntity, Int> {
    @Query(value = "SELECT * FROM ng_auction WHERE world_id = :worldId AND id = :id", nativeQuery = true)
    fun findByWorldIdAndId(@Param("worldId") worldId: Int, @Param("id") id: Int): AuctionEntity?

    @Query(value = "SELECT * FROM ng_auction WHERE world_id = :worldId AND finished = false", nativeQuery = true)
    fun findByWorldIdAndFinishedFalse(@Param("worldId") worldId: Int): List<AuctionEntity>

    @Query(
        value = "SELECT * FROM ng_auction WHERE world_id = :worldId AND type = CAST(:type AS ng_auction_type)",
        nativeQuery = true,
    )
    fun findByWorldIdAndTypeValue(
        @Param("worldId") worldId: Int,
        @Param("type") type: String,
    ): List<AuctionEntity>

    @Query(
        value = """
            SELECT * FROM ng_auction
            WHERE world_id = :worldId
              AND finished = false
              AND type = CAST(:type AS ng_auction_type)
        """,
        nativeQuery = true,
    )
    fun findByWorldIdAndFinishedFalseAndTypeValue(
        @Param("worldId") worldId: Int,
        @Param("type") type: String,
    ): List<AuctionEntity>

    @Query(
        value = """
            SELECT * FROM ng_auction
            WHERE world_id = :worldId
              AND type = CAST(:type AS ng_auction_type)
            ORDER BY close_date ASC
        """,
        nativeQuery = true,
    )
    fun findByWorldIdAndTypeValueOrderByCloseDateAsc(
        @Param("worldId") worldId: Int,
        @Param("type") type: String,
    ): List<AuctionEntity>

    @Query(value = "SELECT COALESCE(MAX(id), 0) FROM ng_auction WHERE world_id = :worldId", nativeQuery = true)
    fun findMaxId(@Param("worldId") worldId: Int): Int
}

internal class WorldScopedAuctionRepository(
    private val raw: AuctionRawRepository,
    private val worldId: WorldId,
) : AuctionRepository {
    override fun findById(id: Int): Optional<AuctionEntity> =
        Optional.ofNullable(raw.findByWorldIdAndId(worldId.value, id))

    override fun findByFinishedFalse(): List<AuctionEntity> =
        raw.findByWorldIdAndFinishedFalse(worldId.value)

    override fun findByTypeValue(type: String): List<AuctionEntity> =
        raw.findByWorldIdAndTypeValue(worldId.value, type)

    override fun findByFinishedFalseAndTypeValue(type: String): List<AuctionEntity> =
        raw.findByWorldIdAndFinishedFalseAndTypeValue(worldId.value, type)

    override fun findByTypeValueOrderByCloseDateAsc(type: String): List<AuctionEntity> =
        raw.findByWorldIdAndTypeValueOrderByCloseDateAsc(worldId.value, type)

    override fun findMaxId(): Int = raw.findMaxId(worldId.value)
}
