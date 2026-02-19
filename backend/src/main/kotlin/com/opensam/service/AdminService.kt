package com.opensam.service

import com.opensam.dto.AdminDashboard
import com.opensam.dto.AdminGeneralSummary
import com.opensam.dto.AdminUserSummary
import com.opensam.dto.AdminWorldInfo
import com.opensam.dto.NationStatistic
import com.opensam.repository.*
import org.springframework.stereotype.Service

@Service
class AdminService(
    private val worldStateRepository: WorldStateRepository,
    private val generalRepository: GeneralRepository,
    private val nationRepository: NationRepository,
    private val cityRepository: CityRepository,
    private val appUserRepository: AppUserRepository,
    private val diplomacyRepository: DiplomacyRepository,
    private val messageRepository: MessageRepository,
) {
    fun getDashboard(): AdminDashboard {
        val worlds = worldStateRepository.findAll()
        val world = worlds.firstOrNull()
        return AdminDashboard(
            worldCount = worlds.size,
            currentWorld = world?.let {
                AdminWorldInfo(
                    id = it.id,
                    year = it.currentYear,
                    month = it.currentMonth,
                    scenarioCode = it.scenarioCode,
                    realtimeMode = it.realtimeMode,
                    config = it.config,
                )
            },
        )
    }

    fun updateSettings(settings: Map<String, Any>): Boolean {
        val world = worldStateRepository.findAll().firstOrNull() ?: return false
        settings["notice"]?.let { world.config["notice"] = it }
        settings["turnTerm"]?.let { world.tickSeconds = (it as Number).toInt() }
        settings["locked"]?.let { world.config["locked"] = it }
        worldStateRepository.save(world)
        return true
    }

    fun listAllGenerals(): List<AdminGeneralSummary> {
        return generalRepository.findAll().map {
            AdminGeneralSummary(
                id = it.id,
                name = it.name,
                nationId = it.nationId,
                crew = it.crew,
                experience = it.experience,
                npcState = it.npcState.toInt(),
                blockState = it.blockState.toInt(),
            )
        }
    }

    fun generalAction(id: Long, type: String): Boolean {
        val general = generalRepository.findById(id).orElse(null) ?: return false
        when (type) {
            "block" -> general.blockState = 1
            "unblock" -> general.blockState = 0
            "kill" -> {
                general.nationId = 0
                general.officerLevel = 0
            }
        }
        generalRepository.save(general)
        return true
    }

    fun getStatistics(): List<NationStatistic> {
        val nations = nationRepository.findAll()
        return nations.map { nation ->
            val generals = generalRepository.findByNationId(nation.id)
            val cities = cityRepository.findByNationId(nation.id)
            NationStatistic(
                nationId = nation.id,
                name = nation.name,
                color = nation.color,
                level = nation.level.toInt(),
                gold = nation.gold,
                rice = nation.rice,
                tech = nation.tech,
                power = nation.power,
                genCount = generals.size,
                cityCount = cities.size,
                totalCrew = generals.sumOf { it.crew },
                totalPop = cities.sumOf { it.pop },
            )
        }
    }

    fun getGeneralLogs(id: Long): List<Any> {
        return messageRepository.findBySrcIdAndMailboxCodeOrderBySentAtDesc(id, "general_record")
    }

    fun getDiplomacyMatrix(): List<Any> {
        return diplomacyRepository.findAll()
    }

    fun timeControl(year: Int?, month: Int?, locked: Boolean?): Boolean {
        val world = worldStateRepository.findAll().firstOrNull() ?: return false
        year?.let { world.currentYear = it.toShort() }
        month?.let { world.currentMonth = it.toShort() }
        locked?.let { world.config["locked"] = it }
        worldStateRepository.save(world)
        return true
    }

    fun listUsers(): List<AdminUserSummary> {
        return appUserRepository.findAll().map {
            AdminUserSummary(
                id = it.id,
                loginId = it.loginId,
                displayName = it.displayName,
                role = it.role,
                createdAt = it.createdAt,
                lastLoginAt = it.lastLoginAt,
            )
        }
    }

    fun userAction(id: Long, type: String): Boolean {
        val user = appUserRepository.findById(id).orElse(null) ?: return false
        when (type) {
            "setAdmin" -> user.role = "ADMIN"
            "removeAdmin" -> user.role = "USER"
            "delete" -> { appUserRepository.delete(user); return true }
        }
        appUserRepository.save(user)
        return true
    }
}
