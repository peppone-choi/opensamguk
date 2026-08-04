package opensamguk.infra.read

import opensamguk.common.world.WorldId
import opensamguk.infra.entity.NgBettingEntity
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.repository.Repository as SpringDataRepository

/**
 * JPA read repository for `ng_betting` table.
 *
 * P7 read API uses this for per-general / per-user bet lookups. The write path
 * (daemon flush) goes through [NgBettingRowMapper] + [JdbcFlushExecutor].
 *
 * Note: the "active" betting concept is a logic-layer filter (KV `BettingInfo.status == OPEN`).
 * This repository returns raw bet rows; the caller joins with the KV betting master.
 */
interface BettingRepository {
    fun findByGeneralId(generalId: Int): List<NgBettingEntity>
    fun findByBettingId(bettingId: Int): List<NgBettingEntity>
    fun findByBettingIdAndGeneralId(bettingId: Int, generalId: Int): List<NgBettingEntity>
    fun aggregateAmountByType(bettingId: Int): List<BettingTypeAggregate>
    fun aggregateAmountByTypeForUser(bettingId: Int, userId: Int): List<BettingTypeAggregate>
    fun sumAmountByBettingIdAndUserId(bettingId: Int, userId: Int): Long
    fun aggregateTotalAmountByBetting(): List<BettingTotalAggregate>
}

internal interface BettingRawRepository : SpringDataRepository<NgBettingEntity, Int> {
    @Query(value = "SELECT * FROM ng_betting WHERE world_id = :worldId AND general_id = :generalId", nativeQuery = true)
    fun findByWorldIdAndGeneralId(
        @Param("worldId") worldId: Int,
        @Param("generalId") generalId: Int,
    ): List<NgBettingEntity>

    @Query(value = "SELECT * FROM ng_betting WHERE world_id = :worldId AND betting_id = :bettingId", nativeQuery = true)
    fun findByWorldIdAndBettingId(
        @Param("worldId") worldId: Int,
        @Param("bettingId") bettingId: Int,
    ): List<NgBettingEntity>

    @Query(
        value = "SELECT * FROM ng_betting WHERE world_id = :worldId AND betting_id = :bettingId AND general_id = :generalId",
        nativeQuery = true,
    )
    fun findByWorldIdAndBettingIdAndGeneralId(
        @Param("worldId") worldId: Int,
        @Param("bettingId") bettingId: Int,
        @Param("generalId") generalId: Int,
    ): List<NgBettingEntity>

    /**
     * W3 — `bettingDetail` 집계. PHP `GetBettingDetail.php:61-64`:
     * `SELECT betting_type, sum(amount) FROM ng_betting WHERE betting_id = ? GROUP BY betting_type`.
     * betting_type별 누적 베팅액(SUM). 베팅이 없으면 빈 목록(NPE/500 아님).
     */
    @Query(
        value = """
            SELECT betting_type AS "bettingType", COALESCE(SUM(amount), 0) AS "sumAmount"
            FROM ng_betting
            WHERE world_id = :worldId AND betting_id = :bettingId
            GROUP BY betting_type
        """,
        nativeQuery = true,
    )
    fun aggregateAmountByType(
        @Param("worldId") worldId: Int,
        @Param("bettingId") bettingId: Int,
    ): List<BettingTypeAggregate>

    /**
     * W3 — per-user `myBetting` 집계. PHP `GetBettingDetail.php:71-76`:
     * `... WHERE betting_id = ? AND user_id = ? GROUP BY betting_type`.
     * 특정 사용자의 betting_type별 누적 베팅액.
     */
    @Query(
        value = """
            SELECT betting_type AS "bettingType", COALESCE(SUM(amount), 0) AS "sumAmount"
            FROM ng_betting
            WHERE world_id = :worldId AND betting_id = :bettingId AND user_id = :userId
            GROUP BY betting_type
        """,
        nativeQuery = true,
    )
    fun aggregateAmountByTypeForUser(
        @Param("worldId") worldId: Int,
        @Param("bettingId") bettingId: Int,
        @Param("userId") userId: Int,
    ): List<BettingTypeAggregate>

    /**
     * P0-07 — bet() 누적 한도 검사용 합계. PHP `Betting.php:135`:
     * `SELECT sum(amount) FROM ng_betting WHERE betting_id = %i AND user_id = %i`.
     * 행이 없으면 0 (PHP `?? 0`).
     */
    @Query(
        value = """
            SELECT COALESCE(SUM(amount), 0) FROM ng_betting
            WHERE world_id = :worldId AND betting_id = :bettingId AND user_id = :userId
        """,
        nativeQuery = true,
    )
    fun sumAmountByBettingIdAndUserId(
        @Param("worldId") worldId: Int,
        @Param("bettingId") bettingId: Int,
        @Param("userId") userId: Int,
    ): Long

    /**
     * D4 — 전체 베팅별 totalAmount 집계. PHP `GetBettingList.php`의
     * `SELECT betting_id, sum(amount) FROM ng_betting GROUP BY betting_id`.
     */
    @Query(
        value = """
            SELECT betting_id AS "bettingId", COALESCE(SUM(amount), 0) AS "sumAmount"
            FROM ng_betting
            WHERE world_id = :worldId
            GROUP BY betting_id
        """,
        nativeQuery = true,
    )
    fun aggregateTotalAmountByBetting(@Param("worldId") worldId: Int): List<BettingTotalAggregate>
}

internal class WorldScopedBettingRepository(
    private val raw: BettingRawRepository,
    private val worldId: WorldId,
) : BettingRepository {
    override fun findByGeneralId(generalId: Int): List<NgBettingEntity> =
        raw.findByWorldIdAndGeneralId(worldId.value, generalId)

    override fun findByBettingId(bettingId: Int): List<NgBettingEntity> =
        raw.findByWorldIdAndBettingId(worldId.value, bettingId)

    override fun findByBettingIdAndGeneralId(bettingId: Int, generalId: Int): List<NgBettingEntity> =
        raw.findByWorldIdAndBettingIdAndGeneralId(worldId.value, bettingId, generalId)

    override fun aggregateAmountByType(bettingId: Int): List<BettingTypeAggregate> =
        raw.aggregateAmountByType(worldId.value, bettingId)

    override fun aggregateAmountByTypeForUser(bettingId: Int, userId: Int): List<BettingTypeAggregate> =
        raw.aggregateAmountByTypeForUser(worldId.value, bettingId, userId)

    override fun sumAmountByBettingIdAndUserId(bettingId: Int, userId: Int): Long =
        raw.sumAmountByBettingIdAndUserId(worldId.value, bettingId, userId)

    override fun aggregateTotalAmountByBetting(): List<BettingTotalAggregate> =
        raw.aggregateTotalAmountByBetting(worldId.value)
}

/**
 * `aggregateTotalAmountByBetting`의 grouped 결과 projection.
 */
interface BettingTotalAggregate {
    val bettingId: Int
    val sumAmount: Long
}

/**
 * `aggregateAmountByType`의 grouped 결과 projection. PHP `[betting_type, sum_amount]` 튜플 동형.
 *  - [bettingType] = `ng_betting.betting_type`(JSON int-array key string, 예: `[1,2]`).
 *  - [sumAmount]   = 해당 type의 누적 베팅액(SUM).
 */
interface BettingTypeAggregate {
    val bettingType: String
    val sumAmount: Long
}
