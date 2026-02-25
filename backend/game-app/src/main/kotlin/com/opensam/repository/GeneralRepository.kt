package com.opensam.repository

import com.opensam.entity.General
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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
    @Query(
        """
        select new com.opensam.repository.GeneralAverageStats(
            coalesce(cast(avg(g.experience) as integer), 0),
            coalesce(cast(avg(g.dedication) as integer), 0)
        )
        from General g
        where g.worldId = :worldId and g.nationId = :nationId
        """,
    )
    fun getAverageStats(
        @Param("worldId") worldId: Long,
        @Param("nationId") nationId: Long,
    ): GeneralAverageStats
}

data class GeneralAverageStats(
    val experience: Int = 0,
    val dedication: Int = 0,
)
