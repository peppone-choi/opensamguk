package com.opensam.repository

import com.opensam.entity.City
import org.springframework.data.jpa.repository.JpaRepository

interface CityRepository : JpaRepository<City, Long> {
    fun findByWorldId(worldId: Long): List<City>
    fun findByNationId(nationId: Long): List<City>
}
