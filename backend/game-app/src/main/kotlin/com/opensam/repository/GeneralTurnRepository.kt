package com.opensam.repository

import com.opensam.entity.GeneralTurn
import org.springframework.data.jpa.repository.JpaRepository

interface GeneralTurnRepository : JpaRepository<GeneralTurn, Long> {
    fun findByWorldId(worldId: Long): List<GeneralTurn>
    fun findByGeneralIdOrderByTurnIdx(generalId: Long): List<GeneralTurn>
    fun deleteByWorldId(worldId: Long)
    fun deleteByGeneralId(generalId: Long)
}
