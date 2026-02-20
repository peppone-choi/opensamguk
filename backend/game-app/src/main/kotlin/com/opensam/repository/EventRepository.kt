package com.opensam.repository

import com.opensam.entity.Event
import org.springframework.data.jpa.repository.JpaRepository

interface EventRepository : JpaRepository<Event, Long> {
    fun findByWorldId(worldId: Long): List<Event>
    fun findByWorldIdAndTargetCodeOrderByPriorityDescIdAsc(worldId: Long, targetCode: String): List<Event>
}
