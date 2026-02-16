package com.opensam.repository

import com.opensam.entity.General
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

interface GeneralRepository : JpaRepository<General, Long> {
    fun findByWorldId(worldId: Long): List<General>
    fun findByNationId(nationId: Long): List<General>
    fun findByCityId(cityId: Long): List<General>
    fun findByUserId(userId: Long): List<General>
    fun findByWorldIdAndUserId(worldId: Long, userId: Long): General?
    fun findByWorldIdAndCommandEndTimeBefore(worldId: Long, time: OffsetDateTime): List<General>
    fun findByTroopId(troopId: Long): List<General>
}
