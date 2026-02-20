package com.opensam.gateway.repository

import com.opensam.gateway.entity.WorldState
import org.springframework.data.jpa.repository.JpaRepository

interface WorldStateRepository : JpaRepository<WorldState, Short>
