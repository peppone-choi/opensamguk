package com.opensam.gateway.service

import com.opensam.gateway.entity.WorldState
import com.opensam.gateway.repository.WorldStateRepository
import org.springframework.stereotype.Service

@Service
class WorldService(
    private val worldStateRepository: WorldStateRepository,
) {
    fun listWorlds(): List<WorldState> = worldStateRepository.findAll()

    fun getWorld(id: Short): WorldState? = worldStateRepository.findById(id).orElse(null)

    fun save(world: WorldState): WorldState = worldStateRepository.save(world)

    fun updateVersionAndActivation(
        world: WorldState,
        commitSha: String,
        gameVersion: String,
        active: Boolean,
    ): WorldState {
        world.commitSha = commitSha
        world.gameVersion = gameVersion
        world.meta["gatewayActive"] = active
        return worldStateRepository.save(world)
    }

    fun markActivation(world: WorldState, active: Boolean): WorldState {
        world.meta["gatewayActive"] = active
        return worldStateRepository.save(world)
    }
}
