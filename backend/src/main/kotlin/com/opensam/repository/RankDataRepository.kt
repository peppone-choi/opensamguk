package com.opensam.repository

import com.opensam.entity.RankData
import org.springframework.data.jpa.repository.JpaRepository

interface RankDataRepository : JpaRepository<RankData, Long> {
    fun findByWorldId(worldId: Long): List<RankData>
    fun findByWorldIdAndCategory(worldId: Long, category: String): List<RankData>
    fun findByWorldIdAndCategoryOrderByScoreDesc(worldId: Long, category: String): List<RankData>
    fun findByNationId(nationId: Long): List<RankData>
}
