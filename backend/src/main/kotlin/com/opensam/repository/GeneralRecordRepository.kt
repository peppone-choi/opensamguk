package com.opensam.repository

import com.opensam.entity.GeneralRecord
import org.springframework.data.jpa.repository.JpaRepository

interface GeneralRecordRepository : JpaRepository<GeneralRecord, Long> {
    fun findByGeneralId(generalId: Long): List<GeneralRecord>
    fun findByWorldId(worldId: Long): List<GeneralRecord>
    fun findByGeneralIdAndRecordType(generalId: Long, recordType: String): List<GeneralRecord>
    fun findByGeneralIdOrderByCreatedAtDesc(generalId: Long): List<GeneralRecord>
}
