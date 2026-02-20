package com.opensam.repository

import com.opensam.entity.BetEntry
import org.springframework.data.jpa.repository.JpaRepository

interface BetEntryRepository : JpaRepository<BetEntry, Long> {
    fun findByBettingId(bettingId: Long): List<BetEntry>
    fun findByGeneralId(generalId: Long): List<BetEntry>
    fun findByBettingIdAndGeneralId(bettingId: Long, generalId: Long): BetEntry?
}
