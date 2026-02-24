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
    fun findByWorldIdAndNationId(worldId: Long, nationId: Long): List<General>
    fun findByNameAndWorldId(name: String, worldId: Long): General?

    /**
     * Get average stats for generals in a nation.
     */
    fun getAverageStats(worldId: Long, nationId: Long): GeneralAverageStats {
        val generals = findByWorldIdAndNationId(worldId, nationId)
        if (generals.isEmpty()) return GeneralAverageStats()
        return GeneralAverageStats(
            experience = (generals.sumOf { it.experience } / generals.size),
            dedication = (generals.sumOf { it.dedication } / generals.size),
        )
    }
}

data class GeneralAverageStats(
    val experience: Int = 0,
    val dedication: Int = 0,
)
