package com.opensam.repository

import com.opensam.entity.VoteCast
import org.springframework.data.jpa.repository.JpaRepository

interface VoteCastRepository : JpaRepository<VoteCast, Long> {
    fun findByVoteId(voteId: Long): List<VoteCast>
    fun findByGeneralId(generalId: Long): List<VoteCast>
    fun findByVoteIdAndGeneralId(voteId: Long, generalId: Long): VoteCast?
}
