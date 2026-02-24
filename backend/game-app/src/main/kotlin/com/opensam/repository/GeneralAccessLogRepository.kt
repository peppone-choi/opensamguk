package com.opensam.repository

import com.opensam.entity.GeneralAccessLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface GeneralAccessLogRepository : JpaRepository<GeneralAccessLog, Long> {
    fun findByGeneralId(generalId: Long): List<GeneralAccessLog>
    fun findByWorldId(worldId: Long): List<GeneralAccessLog>
    fun findByGeneralIdOrderByAccessedAtDesc(generalId: Long): List<GeneralAccessLog>

    @Query(
        """
        SELECT l FROM GeneralAccessLog l
        JOIN General g ON l.generalId = g.id
        WHERE l.worldId = :worldId
        ORDER BY l.refresh DESC
        """
    )
    fun findTopRefreshersByWorldId(worldId: Long): List<GeneralAccessLog>

    @Query("SELECT COALESCE(SUM(l.refresh), 0) FROM GeneralAccessLog l WHERE l.worldId = :worldId")
    fun sumRefreshByWorldId(worldId: Long): Long

    @Query("SELECT COALESCE(SUM(l.refreshScoreTotal), 0) FROM GeneralAccessLog l WHERE l.worldId = :worldId")
    fun sumRefreshScoreTotalByWorldId(worldId: Long): Long
}
