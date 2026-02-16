package com.opensam.repository

import com.opensam.entity.NationTurn
import org.springframework.data.jpa.repository.JpaRepository

interface NationTurnRepository : JpaRepository<NationTurn, Long> {
    fun findByNationIdAndOfficerLevelOrderByTurnIdx(nationId: Long, officerLevel: Short): List<NationTurn>
    fun deleteByNationIdAndOfficerLevel(nationId: Long, officerLevel: Short)
}
