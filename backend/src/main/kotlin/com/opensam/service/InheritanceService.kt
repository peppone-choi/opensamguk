package com.opensam.service

import com.opensam.dto.InheritanceActionResult
import com.opensam.dto.InheritanceInfo
import com.opensam.dto.InheritanceLogEntry
import com.opensam.entity.AppUser
import com.opensam.repository.AppUserRepository
import com.opensam.repository.CityRepository
import org.springframework.stereotype.Service

@Service
class InheritanceService(
    private val appUserRepository: AppUserRepository,
    private val cityRepository: CityRepository,
) {
    companion object {
        // Buff level costs: level 1→5
        private val BUFF_LEVEL_COSTS = intArrayOf(100, 200, 400, 800, 1600)
        private const val MAX_BUFF_LEVEL = 5

        // Inherit special cost
        private const val INHERIT_SPECIAL_COST = 500

        // Inherit city cost
        private const val INHERIT_CITY_COST = 300

        // Buff options with their per-level bonuses
        val BUFF_OPTIONS = mapOf(
            "leadership" to mapOf("label" to "통솔 +1/레벨", "type" to "stat"),
            "strength" to mapOf("label" to "무력 +1/레벨", "type" to "stat"),
            "intel" to mapOf("label" to "지력 +1/레벨", "type" to "stat"),
            "politics" to mapOf("label" to "정치 +1/레벨", "type" to "stat"),
            "charm" to mapOf("label" to "매력 +1/레벨", "type" to "stat"),
            "gold" to mapOf("label" to "초기 금 +500/레벨", "type" to "resource"),
            "rice" to mapOf("label" to "초기 쌀 +500/레벨", "type" to "resource"),
            "crew" to mapOf("label" to "초기 병력 +200/레벨", "type" to "resource"),
            "exp" to mapOf("label" to "초기 경험 +100/레벨", "type" to "resource"),
        )
    }

    fun getInheritance(worldId: Long, loginId: String): InheritanceInfo? {
        val user = appUserRepository.findByLoginId(loginId) ?: return null
        val points = (user.meta["inheritPoints"] as? Number)?.toInt() ?: 0

        @Suppress("UNCHECKED_CAST")
        val rawBuffs = user.meta["inheritBuffs"] as? Map<String, Any> ?: emptyMap()
        val buffs = rawBuffs.mapValues { (it.value as? Number)?.toInt() ?: 0 }

        @Suppress("UNCHECKED_CAST")
        val rawLog = user.meta["inheritLog"] as? List<Map<String, Any>> ?: emptyList()
        val log = rawLog.map { entry ->
            InheritanceLogEntry(
                action = entry["action"] as? String ?: "",
                amount = (entry["amount"] as? Number)?.toInt() ?: 0,
                date = entry["date"] as? String ?: "",
            )
        }

        return InheritanceInfo(points, buffs, log)
    }

    fun buyBuff(worldId: Long, loginId: String, buffCode: String): InheritanceActionResult? {
        val user = appUserRepository.findByLoginId(loginId) ?: return null
        val points = (user.meta["inheritPoints"] as? Number)?.toInt() ?: 0

        // Validate buff code
        if (buffCode !in BUFF_OPTIONS) return InheritanceActionResult(error = "잘못된 버프 코드")

        @Suppress("UNCHECKED_CAST")
        val buffs = (user.meta["inheritBuffs"] as? MutableMap<String, Any>) ?: mutableMapOf()
        val currentLevel = (buffs[buffCode] as? Number)?.toInt() ?: 0
        if (currentLevel >= MAX_BUFF_LEVEL) return InheritanceActionResult(error = "최대 레벨 도달")

        // Calculate cost based on level
        val actualCost = BUFF_LEVEL_COSTS[currentLevel]
        if (points < actualCost) return InheritanceActionResult(error = "포인트 부족 (필요: $actualCost)")

        user.meta["inheritPoints"] = points - actualCost
        buffs[buffCode] = currentLevel + 1
        user.meta["inheritBuffs"] = buffs

        // Add to log
        addInheritLog(user, "버프 구매: ${BUFF_OPTIONS[buffCode]?.get("label")} Lv.${currentLevel + 1}", -actualCost)

        appUserRepository.save(user)
        return InheritanceActionResult(remainingPoints = points - actualCost, newLevel = currentLevel + 1)
    }

    fun setInheritSpecial(worldId: Long, loginId: String, specialCode: String): InheritanceActionResult? {
        val user = appUserRepository.findByLoginId(loginId) ?: return null
        val points = (user.meta["inheritPoints"] as? Number)?.toInt() ?: 0

        if (points < INHERIT_SPECIAL_COST) return InheritanceActionResult(error = "포인트 부족 (필요: $INHERIT_SPECIAL_COST)")

        user.meta["inheritPoints"] = points - INHERIT_SPECIAL_COST
        user.meta["inheritSpecificSpecialWar"] = specialCode
        addInheritLog(user, "전투특기 지정: $specialCode", -INHERIT_SPECIAL_COST)

        appUserRepository.save(user)
        return InheritanceActionResult(remainingPoints = points - INHERIT_SPECIAL_COST)
    }

    fun setInheritCity(worldId: Long, loginId: String, cityId: Long): InheritanceActionResult? {
        val user = appUserRepository.findByLoginId(loginId) ?: return null
        val points = (user.meta["inheritPoints"] as? Number)?.toInt() ?: 0

        if (points < INHERIT_CITY_COST) return InheritanceActionResult(error = "포인트 부족 (필요: $INHERIT_CITY_COST)")

        // Verify city exists
        val city = cityRepository.findById(cityId).orElse(null) ?: return InheritanceActionResult(error = "존재하지 않는 도시")

        user.meta["inheritPoints"] = points - INHERIT_CITY_COST
        user.meta["inheritCity"] = cityId
        addInheritLog(user, "시작 도시 지정: ${city.name}", -INHERIT_CITY_COST)

        appUserRepository.save(user)
        return InheritanceActionResult(remainingPoints = points - INHERIT_CITY_COST)
    }

    /**
     * Accrue inheritance points for a user based on their general's actions.
     * Called from TurnService or command execution.
     */
    fun accruePoints(general: com.opensam.entity.General, key: String, amount: Int) {
        if (general.npcState >= 2) return  // NPCs don't earn points
        val userId = general.userId ?: return
        val user = appUserRepository.findById(userId).orElse(null) ?: return

        val coefficient = when (key) {
            "lived_month" -> 1
            "active_action" -> 3
            "combat" -> 5
            "sabotage" -> 20
            "max_belong" -> 10
            "unifier" -> 1
            "dex" -> 1
            "tournament" -> 1
            "betting" -> 10
            "max_domestic_critical" -> 1
            else -> 1
        }

        val points = amount * coefficient
        val current = (user.meta["inheritPoints"] as? Number)?.toInt() ?: 0
        user.meta["inheritPoints"] = current + points
        appUserRepository.save(user)
    }

    private fun addInheritLog(user: AppUser, action: String, amount: Int) {
        @Suppress("UNCHECKED_CAST")
        val log = (user.meta["inheritLog"] as? MutableList<Map<String, Any>>) ?: mutableListOf()
        log.add(mapOf(
            "action" to action,
            "amount" to amount,
            "date" to java.time.OffsetDateTime.now().toString(),
        ))
        // Keep only last 50 entries
        if (log.size > 50) {
            user.meta["inheritLog"] = log.takeLast(50).toMutableList()
        } else {
            user.meta["inheritLog"] = log
        }
    }
}
