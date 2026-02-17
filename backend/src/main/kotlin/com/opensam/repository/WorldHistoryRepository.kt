package com.opensam.repository

import com.opensam.entity.WorldHistory
import org.springframework.data.jpa.repository.JpaRepository

interface WorldHistoryRepository : JpaRepository<WorldHistory, Long> {
    fun findByWorldId(worldId: Long): List<WorldHistory>
    fun findByWorldIdAndYearAndMonth(worldId: Long, year: Short, month: Short): List<WorldHistory>
    fun findByWorldIdOrderByCreatedAtDesc(worldId: Long): List<WorldHistory>
    fun findByWorldIdAndEventType(worldId: Long, eventType: String): List<WorldHistory>
}
