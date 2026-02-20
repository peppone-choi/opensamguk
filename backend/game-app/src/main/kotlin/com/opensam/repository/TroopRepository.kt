package com.opensam.repository

import com.opensam.entity.Troop
import org.springframework.data.jpa.repository.JpaRepository

interface TroopRepository : JpaRepository<Troop, Long> {
    fun findByWorldId(worldId: Long): List<Troop>
    fun findByNationId(nationId: Long): List<Troop>
}
