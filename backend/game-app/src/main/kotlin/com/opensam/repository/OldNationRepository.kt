package com.opensam.repository

import com.opensam.entity.OldNation
import org.springframework.data.jpa.repository.JpaRepository

interface OldNationRepository : JpaRepository<OldNation, Long> {
    fun findByServerId(serverId: String): List<OldNation>
    fun findByServerIdAndNation(serverId: String, nation: Long): OldNation?
}
