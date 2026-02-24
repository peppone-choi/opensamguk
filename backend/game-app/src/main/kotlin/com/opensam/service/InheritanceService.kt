package com.opensam.service

import com.opensam.dto.AuctionUniqueRequest
import com.opensam.dto.BuyInheritBuffRequest
import com.opensam.dto.CheckOwnerRequest
import com.opensam.dto.CheckOwnerResponse
import com.opensam.dto.CurrentStat
import com.opensam.dto.InheritanceActionCost
import com.opensam.dto.InheritanceActionResult
import com.opensam.dto.InheritanceInfo
import com.opensam.dto.InheritanceLogEntry
import com.opensam.dto.ResetStatsRequest
import com.opensam.dto.SpecialWarOption
import com.opensam.dto.UniqueItemOption
import com.opensam.entity.AppUser
import com.opensam.repository.AppUserRepository
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import org.springframework.stereotype.Service

@Service
class InheritanceService(
    private val appUserRepository: AppUserRepository,
    private val cityRepository: CityRepository,
    private val generalRepository: GeneralRepository,
) {
    companion object {
        // Cumulative buff level costs: index = level (0 = free, 1..5)
        private val BUFF_LEVEL_COSTS = listOf(0, 100, 200, 400, 800, 1600)
        private const val MAX_BUFF_LEVEL = 5

        // Action costs (defaults, can be overridden by game config)
        private const val INHERIT_SPECIAL_COST = 500
        private const val INHERIT_CITY_COST = 300
        private const val RANDOM_UNIQUE_COST = 300
        private const val CHECK_OWNER_COST = 50
        private const val MIN_SPECIFIC_UNIQUE_COST = 500
        private const val BORN_STAT_POINT_COST = 500

        // Valid combat buff types
        val COMBAT_BUFF_TYPES = setOf(
            "warAvoidRatio", "warCriticalRatio", "warMagicTrialProb",
            "warAvoidRatioOppose", "warCriticalRatioOppose", "warMagicTrialProbOppose",
            "domesticSuccessProb", "domesticFailProb",
        )

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

        // Available special war options (should come from game config/static data in production)
        val AVAILABLE_SPECIAL_WAR = mapOf(
            "저격" to SpecialWarOption("저격", "전투 시 상대 장수를 직접 공격하여 부상을 입힙니다."),
            "돌격" to SpecialWarOption("돌격", "전투 시 공격력이 크게 증가합니다."),
            "급습" to SpecialWarOption("급습", "전투 시 선제 공격을 할 수 있습니다."),
            "매복" to SpecialWarOption("매복", "수비 시 추가 피해를 줍니다."),
            "연사" to SpecialWarOption("연사", "궁병 계열 병종의 공격 횟수가 증가합니다."),
            "반계" to SpecialWarOption("반계", "상대의 계략을 되돌려 줍니다."),
            "화공" to SpecialWarOption("화공", "화공 계략의 성공률과 위력이 증가합니다."),
            "치료" to SpecialWarOption("치료", "전투 후 아군 부상자를 치료합니다."),
            "수비" to SpecialWarOption("수비", "수비전 시 방어력이 크게 증가합니다."),
            "위압" to SpecialWarOption("위압", "전투 시 상대 사기를 감소시킵니다."),
        )

        // Available unique items (should come from game config/static data in production)
        val AVAILABLE_UNIQUE = mapOf(
            "적토마" to UniqueItemOption("적토마", "적토마", "이동력 +3, 회피 확률 증가"),
            "의천검" to UniqueItemOption("의천검", "의천검", "무력 +7"),
            "청룡언월도" to UniqueItemOption("청룡언월도", "청룡언월도", "무력 +10"),
            "방천화극" to UniqueItemOption("방천화극", "방천화극", "무력 +12"),
            "태평요술서" to UniqueItemOption("태평요술서", "태평요술서", "지력 +10"),
            "전국옥새" to UniqueItemOption("전국옥새", "전국옥새", "매력 +15"),
            "맹덕신서" to UniqueItemOption("맹덕신서", "맹덕신서", "통솔 +7"),
            "둔갑천서" to UniqueItemOption("둔갑천서", "둔갑천서", "계략 성공률 대폭 증가"),
        )

        private fun fibonacciCost(base: Int, count: Int): Int {
            if (count <= 0) return base
            var a = base
            var b = base
            for (i in 0 until count) {
                val next = a + b
                a = b
                b = next
            }
            return b
        }
    }

    fun getInheritance(worldId: Long, loginId: String): InheritanceInfo? {
        val user = appUserRepository.findByLoginId(loginId) ?: return null
        val points = (user.meta["inheritPoints"] as? Number)?.toInt() ?: 0

        @Suppress("UNCHECKED_CAST")
        val rawBuffs = user.meta["inheritBuffs"] as? Map<String, Any> ?: emptyMap()
        val buffs = rawBuffs.mapValues { (it.value as? Number)?.toInt() ?: 0 }

        @Suppress("UNCHECKED_CAST")
        val rawInheritBuff = user.meta["inheritCombatBuffs"] as? Map<String, Any> ?: emptyMap()
        val inheritBuff = rawInheritBuff.mapValues { (it.value as? Number)?.toInt() ?: 0 }

        @Suppress("UNCHECKED_CAST")
        val rawLog = user.meta["inheritLog"] as? List<Map<String, Any>> ?: emptyList()
        var logId = rawLog.size.toLong()
        val log = rawLog.map { entry ->
            logId--
            InheritanceLogEntry(
                id = logId + 1,
                action = entry["action"] as? String ?: "",
                amount = (entry["amount"] as? Number)?.toInt() ?: 0,
                date = entry["date"] as? String ?: "",
                text = entry["text"] as? String,
            )
        }

        // Point breakdown
        @Suppress("UNCHECKED_CAST")
        val rawBreakdown = user.meta["inheritPointBreakdown"] as? Map<String, Any>
        val pointBreakdown = rawBreakdown?.mapValues { (it.value as? Number)?.toInt() ?: 0 }

        val turnResetCount = (user.meta["inheritTurnResetCount"] as? Number)?.toInt() ?: 0
        val specialWarResetCount = (user.meta["inheritSpecialWarResetCount"] as? Number)?.toInt() ?: 0

        val actionCost = InheritanceActionCost(
            buff = BUFF_LEVEL_COSTS,
            resetTurnTime = fibonacciCost(100, turnResetCount),
            resetSpecialWar = fibonacciCost(200, specialWarResetCount),
            randomUnique = RANDOM_UNIQUE_COST,
            nextSpecial = INHERIT_SPECIAL_COST,
            minSpecificUnique = MIN_SPECIFIC_UNIQUE_COST,
            checkOwner = CHECK_OWNER_COST,
            bornStatPoint = BORN_STAT_POINT_COST,
        )

        // Get current general's stat if exists
        val general = generalRepository.findByWorldIdAndUserId(worldId, user.id!!)
        val currentStat = general?.let {
            CurrentStat(
                leadership = it.leadership,
                strength = it.strength,
                intel = it.intel,
                statMax = 100,
                statMin = 10,
            )
        }

        // Available target generals for owner check (all non-NPC generals in world)
        val allGenerals = generalRepository.findByWorldId(worldId)
        val availableTargetGeneral = allGenerals
            .filter { it.npcState < 2 && it.id != general?.id }
            .associate { it.id!! to it.name }

        return InheritanceInfo(
            points = points,
            pointBreakdown = pointBreakdown,
            buffs = buffs,
            inheritBuff = inheritBuff,
            maxInheritBuff = MAX_BUFF_LEVEL,
            log = log,
            turnResetCount = turnResetCount,
            specialWarResetCount = specialWarResetCount,
            inheritActionCost = actionCost,
            availableSpecialWar = AVAILABLE_SPECIAL_WAR,
            availableUnique = AVAILABLE_UNIQUE,
            availableTargetGeneral = availableTargetGeneral,
            currentStat = currentStat,
        )
    }

    fun buyBuff(worldId: Long, loginId: String, buffCode: String): InheritanceActionResult? {
        val user = appUserRepository.findByLoginId(loginId) ?: return null
        val points = (user.meta["inheritPoints"] as? Number)?.toInt() ?: 0

        if (buffCode !in BUFF_OPTIONS) return InheritanceActionResult(error = "잘못된 버프 코드")

        @Suppress("UNCHECKED_CAST")
        val buffs = (user.meta["inheritBuffs"] as? MutableMap<String, Any>) ?: mutableMapOf()
        val currentLevel = (buffs[buffCode] as? Number)?.toInt() ?: 0
        if (currentLevel >= MAX_BUFF_LEVEL) return InheritanceActionResult(error = "최대 레벨 도달")

        val actualCost = BUFF_LEVEL_COSTS[currentLevel + 1] - BUFF_LEVEL_COSTS[currentLevel]
        if (points < actualCost) return InheritanceActionResult(error = "포인트 부족 (필요: $actualCost)")

        user.meta["inheritPoints"] = points - actualCost
        buffs[buffCode] = currentLevel + 1
        user.meta["inheritBuffs"] = buffs

        addInheritLog(user, "버프 구매: ${BUFF_OPTIONS[buffCode]?.get("label")} Lv.${currentLevel + 1}", -actualCost)

        appUserRepository.save(user)
        return InheritanceActionResult(remainingPoints = points - actualCost, newLevel = currentLevel + 1)
    }

    fun buyInheritBuff(worldId: Long, loginId: String, request: BuyInheritBuffRequest): InheritanceActionResult? {
        val user = appUserRepository.findByLoginId(loginId) ?: return null
        val points = (user.meta["inheritPoints"] as? Number)?.toInt() ?: 0

        if (request.type !in COMBAT_BUFF_TYPES) return InheritanceActionResult(error = "잘못된 전투 버프 타입")
        if (request.level < 1 || request.level > MAX_BUFF_LEVEL) return InheritanceActionResult(error = "잘못된 레벨")

        @Suppress("UNCHECKED_CAST")
        val combatBuffs = (user.meta["inheritCombatBuffs"] as? MutableMap<String, Any>) ?: mutableMapOf()
        val currentLevel = (combatBuffs[request.type] as? Number)?.toInt() ?: 0
        if (request.level <= currentLevel) return InheritanceActionResult(error = "현재 레벨보다 높아야 합니다")

        val cost = BUFF_LEVEL_COSTS[request.level] - BUFF_LEVEL_COSTS[currentLevel]
        if (points < cost) return InheritanceActionResult(error = "포인트 부족 (필요: $cost)")

        user.meta["inheritPoints"] = points - cost
        combatBuffs[request.type] = request.level
        user.meta["inheritCombatBuffs"] = combatBuffs

        addInheritLog(user, "전투버프 구매: ${request.type} Lv.${request.level}", -cost)
        appUserRepository.save(user)
        return InheritanceActionResult(remainingPoints = points - cost, newLevel = request.level)
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

        val city = cityRepository.findById(cityId).orElse(null) ?: return InheritanceActionResult(error = "존재하지 않는 도시")

        user.meta["inheritPoints"] = points - INHERIT_CITY_COST
        user.meta["inheritCity"] = cityId
        addInheritLog(user, "시작 도시 지정: ${city.name}", -INHERIT_CITY_COST)

        appUserRepository.save(user)
        return InheritanceActionResult(remainingPoints = points - INHERIT_CITY_COST)
    }

    fun resetTurn(worldId: Long, loginId: String): InheritanceActionResult? {
        val user = appUserRepository.findByLoginId(loginId) ?: return null
        val points = (user.meta["inheritPoints"] as? Number)?.toInt() ?: 0
        val resetCount = (user.meta["inheritTurnResetCount"] as? Number)?.toInt() ?: 0
        val cost = fibonacciCost(100, resetCount)

        if (points < cost) return InheritanceActionResult(error = "포인트 부족 (필요: $cost)")

        user.meta["inheritPoints"] = points - cost
        user.meta["inheritTurnResetCount"] = resetCount + 1
        user.meta["inheritResetTurn"] = true
        addInheritLog(user, "턴 시간 초기화 (${resetCount + 1}회차)", -cost)

        appUserRepository.save(user)
        return InheritanceActionResult(remainingPoints = points - cost)
    }

    fun buyRandomUnique(worldId: Long, loginId: String): InheritanceActionResult? {
        val user = appUserRepository.findByLoginId(loginId) ?: return null
        val points = (user.meta["inheritPoints"] as? Number)?.toInt() ?: 0

        if (points < RANDOM_UNIQUE_COST) return InheritanceActionResult(error = "포인트 부족 (필요: $RANDOM_UNIQUE_COST)")

        user.meta["inheritPoints"] = points - RANDOM_UNIQUE_COST
        user.meta["inheritRandomUnique"] = true
        addInheritLog(user, "랜덤 유니크 획득", -RANDOM_UNIQUE_COST)

        appUserRepository.save(user)
        return InheritanceActionResult(remainingPoints = points - RANDOM_UNIQUE_COST)
    }

    fun resetSpecialWar(worldId: Long, loginId: String): InheritanceActionResult? {
        val user = appUserRepository.findByLoginId(loginId) ?: return null
        val points = (user.meta["inheritPoints"] as? Number)?.toInt() ?: 0
        val resetCount = (user.meta["inheritSpecialWarResetCount"] as? Number)?.toInt() ?: 0
        val cost = fibonacciCost(200, resetCount)

        if (points < cost) return InheritanceActionResult(error = "포인트 부족 (필요: $cost)")

        user.meta["inheritPoints"] = points - cost
        user.meta["inheritSpecialWarResetCount"] = resetCount + 1
        // Actual reset of special war would be done by game engine
        addInheritLog(user, "전투특기 초기화 (${resetCount + 1}회차)", -cost)

        appUserRepository.save(user)
        return InheritanceActionResult(remainingPoints = points - cost)
    }

    fun resetStats(worldId: Long, loginId: String, request: ResetStatsRequest): InheritanceActionResult? {
        val user = appUserRepository.findByLoginId(loginId) ?: return null
        val points = (user.meta["inheritPoints"] as? Number)?.toInt() ?: 0

        val hasBonusStat = request.inheritBonusStat?.any { it > 0 } == true
        val cost = if (hasBonusStat) BORN_STAT_POINT_COST else 0

        if (points < cost) return InheritanceActionResult(error = "포인트 부족 (필요: $cost)")

        // Check if already reset this season
        if (user.meta["inheritStatResetDone"] == true) {
            return InheritanceActionResult(error = "이미 이번 시즌에 능력치를 초기화했습니다")
        }

        val general = generalRepository.findByWorldIdAndUserId(worldId, user.id!!)
            ?: return InheritanceActionResult(error = "장수를 찾을 수 없습니다")

        // Update general stats
        general.leadership = request.leadership
        general.strength = request.strength
        general.intel = request.intel

        // Apply bonus stat if present
        if (hasBonusStat && request.inheritBonusStat != null) {
            general.leadership += request.inheritBonusStat[0]
            general.strength += request.inheritBonusStat[1]
            general.intel += request.inheritBonusStat[2]
        }

        generalRepository.save(general)

        if (cost > 0) {
            user.meta["inheritPoints"] = points - cost
        }
        user.meta["inheritStatResetDone"] = true
        addInheritLog(user, "능력치 초기화 (통${request.leadership}/무${request.strength}/지${request.intel})", -cost)

        appUserRepository.save(user)
        return InheritanceActionResult(remainingPoints = points - cost)
    }

    fun checkOwner(worldId: Long, loginId: String, request: CheckOwnerRequest): CheckOwnerResponse? {
        val user = appUserRepository.findByLoginId(loginId) ?: return null
        val points = (user.meta["inheritPoints"] as? Number)?.toInt() ?: 0

        if (points < CHECK_OWNER_COST) return CheckOwnerResponse(found = false)

        val general = when {
            request.destGeneralID != null -> generalRepository.findById(request.destGeneralID).orElse(null)
            request.generalName != null -> generalRepository.findByNameAndWorldId(request.generalName, worldId)
            else -> null
        } ?: return CheckOwnerResponse(found = false)

        val ownerUser = general.userId?.let { appUserRepository.findById(it).orElse(null) }

        user.meta["inheritPoints"] = points - CHECK_OWNER_COST
        addInheritLog(user, "소유주 확인: ${general.name}", -CHECK_OWNER_COST)
        appUserRepository.save(user)

        return if (ownerUser != null) {
            CheckOwnerResponse(ownerName = ownerUser.loginId, found = true)
        } else {
            CheckOwnerResponse(found = false)
        }
    }

    fun getMoreLog(worldId: Long, loginId: String, lastID: Long): List<InheritanceLogEntry> {
        val user = appUserRepository.findByLoginId(loginId) ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        val rawLog = user.meta["inheritLog"] as? List<Map<String, Any>> ?: emptyList()

        var logId = rawLog.size.toLong()
        val allLogs = rawLog.map { entry ->
            logId--
            InheritanceLogEntry(
                id = logId + 1,
                action = entry["action"] as? String ?: "",
                amount = (entry["amount"] as? Number)?.toInt() ?: 0,
                date = entry["date"] as? String ?: "",
                text = entry["text"] as? String,
            )
        }

        return allLogs.filter { (it.id ?: Long.MAX_VALUE) < lastID }.take(20)
    }

    fun auctionUnique(worldId: Long, loginId: String, request: AuctionUniqueRequest): InheritanceActionResult? {
        val user = appUserRepository.findByLoginId(loginId) ?: return null
        val points = (user.meta["inheritPoints"] as? Number)?.toInt() ?: 0

        if (request.bidAmount < MIN_SPECIFIC_UNIQUE_COST) {
            return InheritanceActionResult(error = "최소 입찰금: ${MIN_SPECIFIC_UNIQUE_COST}P")
        }
        if (points < request.bidAmount) {
            return InheritanceActionResult(error = "포인트 부족 (필요: ${request.bidAmount})")
        }

        user.meta["inheritPoints"] = points - request.bidAmount
        user.meta["inheritAuctionUnique"] = request.uniqueCode
        user.meta["inheritAuctionBid"] = request.bidAmount

        val uniqueName = AVAILABLE_UNIQUE[request.uniqueCode]?.title ?: request.uniqueCode
        addInheritLog(user, "유니크 입찰: $uniqueName (${request.bidAmount}P)", -request.bidAmount)

        appUserRepository.save(user)
        return InheritanceActionResult(remainingPoints = points - request.bidAmount)
    }

    /**
     * Accrue inheritance points for a user based on their general's actions.
     */
    fun accruePoints(general: com.opensam.entity.General, key: String, amount: Int) {
        if (general.npcState >= 2) return
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
        if (log.size > 50) {
            user.meta["inheritLog"] = log.takeLast(50).toMutableList()
        } else {
            user.meta["inheritLog"] = log
        }
    }
}
