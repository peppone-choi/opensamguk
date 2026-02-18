package com.opensam.repository

import com.opensam.entity.HallOfFame
import org.springframework.data.jpa.repository.JpaRepository

interface HallOfFameRepository : JpaRepository<HallOfFame, Long> {
    fun findByServerIdAndTypeAndGeneralNo(serverId: String, type: String, generalNo: Long): HallOfFame?
}
