package com.opensam.repository

import com.opensam.entity.GameHistory
import org.springframework.data.jpa.repository.JpaRepository

interface GameHistoryRepository : JpaRepository<GameHistory, String> {
    fun findByServerId(serverId: String): GameHistory?
}
