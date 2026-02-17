package com.opensam.repository

import com.opensam.entity.GeneralAccessLog
import org.springframework.data.jpa.repository.JpaRepository

interface GeneralAccessLogRepository : JpaRepository<GeneralAccessLog, Long> {
    fun findByGeneralId(generalId: Long): List<GeneralAccessLog>
    fun findByWorldId(worldId: Long): List<GeneralAccessLog>
    fun findByGeneralIdOrderByAccessedAtDesc(generalId: Long): List<GeneralAccessLog>
}
