package com.opensam.repository

import com.opensam.entity.NationFlag
import com.opensam.entity.NationFlagId
import org.springframework.data.jpa.repository.JpaRepository

interface NationFlagRepository : JpaRepository<NationFlag, NationFlagId>
