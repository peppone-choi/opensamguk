package com.opensam.service

import com.opensam.dto.CreateGeneralRequest
import com.opensam.entity.General
import com.opensam.repository.AppUserRepository
import com.opensam.repository.GeneralRepository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

@Service
class GeneralService(
    private val generalRepository: GeneralRepository,
    private val appUserRepository: AppUserRepository,
) {
    fun listByWorld(worldId: Long): List<General> {
        return generalRepository.findByWorldId(worldId)
    }

    fun getById(id: Long): General? {
        return generalRepository.findById(id).orElse(null)
    }

    fun getMyGeneral(worldId: Long, loginId: String): General? {
        val userId = getCurrentUserId(loginId) ?: return null
        return generalRepository.findByWorldIdAndUserId(worldId, userId)
    }

    fun listByNation(nationId: Long): List<General> {
        return generalRepository.findByNationId(nationId)
    }

    fun listByCity(cityId: Long): List<General> {
        return generalRepository.findByCityId(cityId)
    }

    fun createGeneral(worldId: Long, loginId: String, request: CreateGeneralRequest): General? {
        val userId = getCurrentUserId(loginId) ?: return null
        val general = General(
            worldId = worldId,
            userId = userId,
            name = request.name,
            cityId = request.cityId,
            nationId = request.nationId,
            leadership = request.leadership,
            strength = request.strength,
            intel = request.intel,
            politics = request.politics,
            charm = request.charm,
            crewType = request.crewType,
            turnTime = OffsetDateTime.now(),
        )
        return generalRepository.save(general)
    }

    fun listAvailableNpcs(worldId: Long): List<General> {
        return generalRepository.findByWorldId(worldId)
            .filter { it.npcState.toInt() == 1 && it.userId == null }
    }

    fun possessNpc(worldId: Long, loginId: String, generalId: Long): General? {
        val userId = getCurrentUserId(loginId) ?: return null
        val existing = generalRepository.findByWorldIdAndUserId(worldId, userId)
        if (existing != null) return null
        val general = generalRepository.findById(generalId).orElse(null) ?: return null
        if (general.worldId != worldId || general.npcState.toInt() == 0 || general.userId != null) return null
        general.userId = userId
        general.npcState = 0
        return generalRepository.save(general)
    }

    fun listPool(worldId: Long): List<General> {
        return generalRepository.findByWorldId(worldId)
            .filter { it.npcState.toInt() == 5 && it.userId == null }
    }

    fun selectFromPool(worldId: Long, loginId: String, generalId: Long): General? {
        val userId = getCurrentUserId(loginId) ?: return null
        val existing = generalRepository.findByWorldIdAndUserId(worldId, userId)
        if (existing != null) return null
        val general = generalRepository.findById(generalId).orElse(null) ?: return null
        if (general.worldId != worldId || general.npcState.toInt() != 5 || general.userId != null) return null
        general.userId = userId
        general.npcState = 0
        return generalRepository.save(general)
    }

    fun buildPoolGeneral(worldId: Long, loginId: String, name: String, leadership: Short, strength: Short, intel: Short, politics: Short, charm: Short): General? {
        val userId = getCurrentUserId(loginId) ?: return null
        // Check if the user already has a general in this world (pool or otherwise)
        val existing = generalRepository.findByWorldIdAndUserId(worldId, userId)
        if (existing != null) return null
        val general = General(
            worldId = worldId,
            userId = userId,
            name = name,
            cityId = 0,
            nationId = 0,
            leadership = leadership,
            strength = strength,
            intel = intel,
            politics = politics,
            charm = charm,
            npcState = 5, // pool state
            turnTime = OffsetDateTime.now(),
        )
        return generalRepository.save(general)
    }

    fun updatePoolGeneral(worldId: Long, loginId: String, generalId: Long, leadership: Short, strength: Short, intel: Short, politics: Short, charm: Short): General? {
        val userId = getCurrentUserId(loginId) ?: return null
        val general = generalRepository.findById(generalId).orElse(null) ?: return null
        // Only owner can update their own pool general
        if (general.worldId != worldId || general.userId != userId || general.npcState.toInt() != 5) return null
        general.leadership = leadership
        general.strength = strength
        general.intel = intel
        general.politics = politics
        general.charm = charm
        return generalRepository.save(general)
    }

    fun getMyActiveGeneral(loginId: String): General? {
        val userId = getCurrentUserId(loginId) ?: return null
        return generalRepository.findByUserId(userId).firstOrNull { it.npcState.toInt() == 0 }
    }

    fun getCurrentUserId(loginId: String): Long? {
        return appUserRepository.findByLoginId(loginId)?.id
    }
}
