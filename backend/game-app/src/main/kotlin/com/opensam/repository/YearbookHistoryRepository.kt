package com.opensam.repository

import com.opensam.entity.YearbookHistory
import org.springframework.data.jpa.repository.JpaRepository

interface YearbookHistoryRepository : JpaRepository<YearbookHistory, Long> {
    fun findByWorldIdAndYearAndMonth(worldId: Long, year: Short, month: Short): YearbookHistory?
}
