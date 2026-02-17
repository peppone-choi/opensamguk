package com.opensam.repository

import com.opensam.entity.Diplomacy
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DiplomacyRepository : JpaRepository<Diplomacy, Long> {
    fun findByWorldId(worldId: Long): List<Diplomacy>
    fun findByWorldIdAndIsDeadFalse(worldId: Long): List<Diplomacy>
    fun findByWorldIdAndSrcNationIdOrDestNationId(worldId: Long, srcNationId: Long, destNationId: Long): List<Diplomacy>

    @Query("""
        SELECT d FROM Diplomacy d
        WHERE d.worldId = :worldId AND d.isDead = false
          AND ((d.srcNationId = :nationA AND d.destNationId = :nationB)
            OR (d.srcNationId = :nationB AND d.destNationId = :nationA))
          AND d.stateCode = :stateCode
    """)
    fun findActiveRelation(
        @Param("worldId") worldId: Long,
        @Param("nationA") nationA: Long,
        @Param("nationB") nationB: Long,
        @Param("stateCode") stateCode: String,
    ): Diplomacy?

    @Query("""
        SELECT d FROM Diplomacy d
        WHERE d.worldId = :worldId AND d.isDead = false
          AND ((d.srcNationId = :nationA AND d.destNationId = :nationB)
            OR (d.srcNationId = :nationB AND d.destNationId = :nationA))
    """)
    fun findActiveRelationsBetween(
        @Param("worldId") worldId: Long,
        @Param("nationA") nationA: Long,
        @Param("nationB") nationB: Long,
    ): List<Diplomacy>
}
