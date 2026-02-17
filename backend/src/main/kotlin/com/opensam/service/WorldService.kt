package com.opensam.service

import com.opensam.entity.WorldState
import com.opensam.repository.WorldStateRepository
import org.springframework.stereotype.Service

@Service
class WorldService(
    private val worldStateRepository: WorldStateRepository,
) {
    fun listWorlds(): List<WorldState> {
        return worldStateRepository.findAll()
    }

    fun getWorld(id: Short): WorldState? {
        return worldStateRepository.findById(id).orElse(null)
    }

    fun save(world: WorldState): WorldState {
        return worldStateRepository.save(world)
    }

    fun deleteWorld(id: Short) {
        worldStateRepository.deleteById(id)
    }
}
