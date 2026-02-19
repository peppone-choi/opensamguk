package com.opensam.repository

import com.opensam.entity.NationTurn
import org.springframework.data.jpa.repository.JpaRepository

interface NationTurnRepository : JpaRepository<NationTurn, Long> {
    fun findByWorldId(worldId: Long): List<NationTurn>
    fun findByNationIdAndOfficerLevelOrderByTurnIdx(nationId: Long, officerLevel: Short): List<NationTurn>
    fun deleteByWorldId(worldId: Long)
    fun deleteByNationIdAndOfficerLevel(nationId: Long, officerLevel: Short)
}
