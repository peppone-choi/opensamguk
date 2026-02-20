package com.opensam.repository

import com.opensam.entity.WorldState
import org.springframework.data.jpa.repository.JpaRepository

interface WorldStateRepository : JpaRepository<WorldState, Short> {
    fun findByCommitSha(commitSha: String): List<WorldState>
}
