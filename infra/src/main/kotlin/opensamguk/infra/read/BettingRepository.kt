package opensamguk.infra.read

import opensamguk.infra.entity.NgBettingEntity
import org.springframework.data.jpa.repository.JpaRepository
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

}
