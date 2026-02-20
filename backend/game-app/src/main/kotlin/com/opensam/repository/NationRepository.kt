package com.opensam.repository

import com.opensam.entity.Nation
import org.springframework.data.jpa.repository.JpaRepository

interface NationRepository : JpaRepository<Nation, Long> {
    fun findByWorldId(worldId: Long): List<Nation>
}
