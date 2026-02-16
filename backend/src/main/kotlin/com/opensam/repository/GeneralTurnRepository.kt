package com.opensam.repository

import com.opensam.entity.GeneralTurn
import org.springframework.data.jpa.repository.JpaRepository

interface GeneralTurnRepository : JpaRepository<GeneralTurn, Long> {
    fun findByGeneralIdOrderByTurnIdx(generalId: Long): List<GeneralTurn>
    fun deleteByGeneralId(generalId: Long)
}
