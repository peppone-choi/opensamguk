package com.opensam.service

import com.opensam.dto.AdminDashboard
import com.opensam.dto.AdminGeneralSummary
import com.opensam.dto.AdminUserAction
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
    private companion object {
        const val GRADE_SYSTEM_ADMIN = 6
    }

    fun getDashboard(worldId: Long): AdminDashboard {
        val worlds = worldStateRepository.findAll()
        val world = worlds.firstOrNull { it.id.toLong() == worldId }
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

    fun updateSettings(worldId: Long, settings: Map<String, Any>): Boolean {
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null) ?: return false
        settings["notice"]?.let { world.config["notice"] = it }
        settings["turnTerm"]?.let { world.tickSeconds = (it as Number).toInt() }
        settings["locked"]?.let { world.config["locked"] = it }
        worldStateRepository.save(world)
        return true
    }

    fun listAllGenerals(worldId: Long): List<AdminGeneralSummary> {
        return generalRepository.findByWorldId(worldId).map {
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

    fun generalAction(worldId: Long, id: Long, type: String): Boolean {
        val general = generalRepository.findById(id).orElse(null) ?: return false
        if (general.worldId != worldId) return false
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

    fun getStatistics(worldId: Long): List<NationStatistic> {
        val nations = nationRepository.findByWorldId(worldId)
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

    fun getGeneralLogs(worldId: Long, id: Long): List<Any> {
        return messageRepository.findByWorldIdAndMailboxCodeAndSrcIdOrderBySentAtDesc(worldId, "general_record", id)
    }

    fun getDiplomacyMatrix(worldId: Long): List<Any> {
        return diplomacyRepository.findByWorldId(worldId)
    }

    fun writeLog(worldId: Long, message: String): Boolean {
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null) ?: return false
        val msg = com.opensam.entity.Message(
            worldId = worldId,
            mailboxCode = "world_history",
            mailboxType = "PUBLIC",
            messageType = "admin_log",
            payload = mutableMapOf("text" to message as Any),
            meta = mutableMapOf("year" to (world.currentYear.toInt() as Any), "month" to (world.currentMonth.toInt() as Any)),
        )
        messageRepository.save(msg)
        return true
    }

    fun timeControl(worldId: Long, year: Int?, month: Int?, locked: Boolean?): Boolean {
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null) ?: return false
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
                grade = it.grade.toInt(),
                createdAt = it.createdAt,
                lastLoginAt = it.lastLoginAt,
            )
        }
    }

    fun userAction(actorLoginId: String, id: Long, action: AdminUserAction): Boolean {
        val actor = appUserRepository.findByLoginId(actorLoginId) ?: return false
        val actorGrade = actor.grade.toInt()
        if (actorGrade < GRADE_SYSTEM_ADMIN) {
            return false
        }

        val user = appUserRepository.findById(id).orElse(null) ?: return false
        val targetGrade = user.grade.toInt()
        if (targetGrade >= actorGrade) {
            return false
        }

        when (action.type) {
            "delete" -> {
                appUserRepository.delete(user)
                return true
            }
            "setAdmin", "removeAdmin", "setGrade" -> {
                val nextGrade = resolveNextGrade(action) ?: return false
                if (nextGrade !in 0..7) {
                    return false
                }
                if (nextGrade >= actorGrade) {
                    return false
                }
                user.grade = nextGrade.toShort()
                user.role = if (nextGrade >= 5) "ADMIN" else "USER"
            }
            else -> return false
        }
        appUserRepository.save(user)
        return true
    }

    private fun resolveNextGrade(action: AdminUserAction): Int? {
        return when (action.type) {
            "setAdmin" -> 6
            "removeAdmin" -> 1
            "setGrade" -> action.grade
            else -> null
        }
    }
}
