package opensamguk.infra.read

import opensamguk.infra.entity.NgBettingEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * JPA read repository for `ng_betting` table.
 *
 * P7 read API uses this for per-general / per-user bet lookups. The write path
 * (daemon flush) goes through [NgBettingRowMapper] + [JdbcFlushExecutor].
 *
 * Note: the "active" betting concept is a logic-layer filter (KV `BettingInfo.status == OPEN`).
 * This repository returns raw bet rows; the caller joins with the KV betting master.
 */
@Repository
interface BettingRepository : JpaRepository<NgBettingEntity, Int> {

    /** All bets placed by a general. */
    fun findByGeneralId(generalId: Int): List<NgBettingEntity>

    /** All bets for a specific betting master id. */
    fun findByBettingId(bettingId: Int): List<NgBettingEntity>

    /** All bets for a specific betting master + general. */
    fun findByBettingIdAndGeneralId(bettingId: Int, generalId: Int): List<NgBettingEntity>

    /**
     * W3 — `bettingDetail` 집계. PHP `GetBettingDetail.php:61-64`:
     * `SELECT betting_type, sum(amount) FROM ng_betting WHERE betting_id = ? GROUP BY betting_type`.
     * betting_type별 누적 베팅액(SUM). 베팅이 없으면 빈 목록(NPE/500 아님).
     */
    @Query(
        "select b.bettingType as bettingType, coalesce(sum(b.amount), 0) as sumAmount " +
            "from NgBettingEntity b where b.bettingId = :bettingId group by b.bettingType",
    )
    fun aggregateAmountByType(@Param("bettingId") bettingId: Int): List<BettingTypeAggregate>

    /**
     * W3 — per-user `myBetting` 집계. PHP `GetBettingDetail.php:71-76`:
     * `... WHERE betting_id = ? AND user_id = ? GROUP BY betting_type`.
     * 특정 사용자의 betting_type별 누적 베팅액.
     */
    @Query(
        "select b.bettingType as bettingType, coalesce(sum(b.amount), 0) as sumAmount " +
            "from NgBettingEntity b where b.bettingId = :bettingId and b.userId = :userId " +
            "group by b.bettingType",
    )
    fun aggregateAmountByTypeForUser(
        @Param("bettingId") bettingId: Int,
        @Param("userId") userId: Int,
    ): List<BettingTypeAggregate>

    /**
     * D4 — 전체 베팅별 totalAmount 집계. PHP `GetBettingList.php`의
     * `SELECT betting_id, sum(amount) FROM ng_betting GROUP BY betting_id`.
     */
    @Query(
        "select b.bettingId as bettingId, coalesce(sum(b.amount), 0) as sumAmount " +
            "from NgBettingEntity b group by b.bettingId",
    )
    fun aggregateTotalAmountByBetting(): List<BettingTotalAggregate>
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
