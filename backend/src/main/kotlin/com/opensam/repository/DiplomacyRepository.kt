package com.opensam.repository

import com.opensam.entity.Diplomacy
import org.springframework.data.jpa.repository.JpaRepository

interface DiplomacyRepository : JpaRepository<Diplomacy, Long> {
    fun findByWorldId(worldId: Long): List<Diplomacy>
    fun findByWorldIdAndIsDeadFalse(worldId: Long): List<Diplomacy>
    fun findByWorldIdAndSrcNationIdOrDestNationId(worldId: Long, srcNationId: Long, destNationId: Long): List<Diplomacy>
}
