package com.opensam.repository

import com.opensam.entity.TrafficSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TrafficSnapshotRepository : JpaRepository<TrafficSnapshot, Long> {
    fun findTop30ByWorldIdOrderByRecordedAtDesc(worldId: Long): List<TrafficSnapshot>

    @Query("SELECT COALESCE(MAX(t.refresh), 0) FROM TrafficSnapshot t WHERE t.worldId = :worldId")
    fun findMaxRefresh(worldId: Long): Int

    @Query("SELECT COALESCE(MAX(t.online), 0) FROM TrafficSnapshot t WHERE t.worldId = :worldId")
    fun findMaxOnline(worldId: Long): Int
}
