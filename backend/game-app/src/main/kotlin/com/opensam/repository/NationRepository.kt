package com.opensam.repository

import com.opensam.entity.Nation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface NationRepository : JpaRepository<Nation, Long> {
    fun findByWorldId(worldId: Long): List<Nation>
    fun findByWorldIdAndName(worldId: Long, name: String): Nation?

    @Query("SELECT COALESCE(AVG(n.gennum), 0) FROM Nation n WHERE n.worldId = :worldId AND n.level > 0")
    fun getAverageGennum(worldId: Long): Double
}
