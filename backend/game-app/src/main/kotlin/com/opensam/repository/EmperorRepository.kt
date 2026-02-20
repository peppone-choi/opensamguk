package com.opensam.repository

import com.opensam.entity.Emperor
import org.springframework.data.jpa.repository.JpaRepository

interface EmperorRepository : JpaRepository<Emperor, Long>
