package com.opensam.repository

import com.opensam.entity.Tournament
import org.springframework.data.jpa.repository.JpaRepository

interface TournamentRepository : JpaRepository<Tournament, Long> {
    fun findByWorldId(worldId: Long): List<Tournament>
    fun findByWorldIdOrderByRoundAscBracketPositionAsc(worldId: Long): List<Tournament>
    fun findByGeneralId(generalId: Long): List<Tournament>
    fun findByWorldIdAndRoundOrderByBracketPositionAsc(worldId: Long, round: Short): List<Tournament>
    fun findByWorldIdAndRound(worldId: Long, round: Short): List<Tournament>
    fun deleteByWorldId(worldId: Long): Long
}
