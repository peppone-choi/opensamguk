package com.opensam.service

import com.opensam.entity.City
import com.opensam.repository.CityRepository
import org.springframework.stereotype.Service

@Service
class CityService(
    private val cityRepository: CityRepository,
) {
    fun listByWorld(worldId: Long): List<City> {
        return cityRepository.findByWorldId(worldId)
    }

    fun getById(id: Long): City? {
        return cityRepository.findById(id).orElse(null)
    }

    fun listByNation(nationId: Long): List<City> {
        return cityRepository.findByNationId(nationId)
    }
}
