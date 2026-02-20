package com.opensam.engine

import com.opensam.entity.Emperor
import com.opensam.entity.GameHistory
import com.opensam.entity.General
import com.opensam.entity.HallOfFame
import com.opensam.entity.Message
import com.opensam.entity.OldGeneral
import com.opensam.entity.OldNation
import com.opensam.entity.WorldState
import com.opensam.repository.AppUserRepository
import com.opensam.repository.CityRepository
import com.opensam.repository.EmperorRepository
import com.opensam.repository.GameHistoryRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.HallOfFameRepository
import com.opensam.repository.MessageRepository
import com.opensam.repository.NationRepository
import com.opensam.repository.OldGeneralRepository
import com.opensam.repository.OldNationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class UnificationService(
    private val nationRepository: NationRepository,
    private val cityRepository: CityRepository,
    private val generalRepository: GeneralRepository,
    private val appUserRepository: AppUserRepository,
    private val hallOfFameRepository: HallOfFameRepository,
    private val emperorRepository: EmperorRepository,
    private val oldNationRepository: OldNationRepository,
    private val oldGeneralRepository: OldGeneralRepository,
    private val gameHistoryRepository: GameHistoryRepository,
    private val messageRepository: MessageRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val UNIFIER_POINT = 2000
    }

    @Transactional
    fun checkAndSettleUnification(world: WorldState) {
        val isUnited = (world.config["isUnited"] as? Number)?.toInt() ?: 0
        if (isUnited != 0) {
            return
        }

        val worldId = world.id.toLong()
        val nations = nationRepository.findByWorldId(worldId)
        val activeNations = nations.filter { it.level > 0 }
        if (activeNations.size != 1) {
            return
        }

        val winner = activeNations.first()
        val cities = cityRepository.findByWorldId(worldId)
        if (cities.isEmpty()) {
            return
        }

        val ownedCount = cities.count { it.nationId == winner.id }
        if (ownedCount != cities.size) {
            return
        }

        world.config["isUnited"] = 2
        val logText = "【통일】${winner.name}이 전토를 통일하였습니다."
        logger.info("World {} united by nation {} ({})", worldId, winner.name, winner.id)
        messageRepository.save(
            Message(
                worldId = worldId,
                mailboxCode = "world_history",
                messageType = "history",
                payload = mutableMapOf(
                    "message" to logText,
                    "year" to world.currentYear.toInt(),
                    "month" to world.currentMonth.toInt(),
                )
            )
        )

        settleInheritance(world, winner.id)
        settleHallOfFame(world, winner.id)
        settleDynasty(world, winner.id, winner.name)
    }

    private fun settleInheritance(world: WorldState, winnerNationId: Long) {
        val generals = generalRepository.findByWorldId(world.id.toLong())
            .filter { it.userId != null && it.npcState.toInt() < 2 }
        for (general in generals) {
            val userId = general.userId ?: continue
            val user = appUserRepository.findById(userId).orElse(null) ?: continue

            val inheritMeta = asMap(general.meta["inherit"])
            val rankMeta = asMap(general.meta["rank"])
            val livedMonth = readNumber(general.meta, "inherit_lived_month")
                + readNumber(inheritMeta, "lived_month")
            val maxDomestic = readNumber(general.meta, "max_domestic_critical")
            val activeAction = readNumber(general.meta, "inherit_active_action")
                + readNumber(inheritMeta, "active_action")
            val warnum = readNumber(rankMeta, "warnum") + readNumber(general.meta, "rank_warnum")
            val firenum = readNumber(rankMeta, "firenum") + readNumber(general.meta, "firenum")
            val combat = warnum * 5
            val sabotage = firenum * 20
            val dex = computeDexPoint(general)
            val previousUnifier = readNumber(inheritMeta, "unifier")
            val unifierAward = if (general.nationId == winnerNationId && general.officerLevel.toInt() > 4) {
                UNIFIER_POINT
            } else {
                0
            }

            val earned = livedMonth + maxDomestic + activeAction * 3 + combat + sabotage + dex + previousUnifier + unifierAward
            if (earned <= 0) {
                continue
            }

            val current = (user.meta["inheritPoints"] as? Number)?.toInt() ?: 0
            user.meta["inheritPoints"] = current + earned

            val log = mutableListOf<Map<String, Any>>()
            val oldLog = user.meta["inheritLog"] as? List<*>
            oldLog?.forEach {
                val row = it as? Map<*, *> ?: return@forEach
                @Suppress("UNCHECKED_CAST")
                log.add(row as Map<String, Any>)
            }
            log.add(
                mapOf(
                    "action" to "천하 통일 정산",
                    "amount" to earned,
                    "date" to OffsetDateTime.now().toString(),
                )
            )
            user.meta["inheritLog"] = log.takeLast(50).toMutableList()

            appUserRepository.save(user)
        }
    }

    private fun settleHallOfFame(world: WorldState, winnerNationId: Long) {
        val worldId = world.id.toLong()
        val serverId = readString(world.config, "serverId").ifBlank { world.name }
        val season = readNumber(world.meta, "season").takeIf { it > 0 } ?: 1
        val scenario = readNumber(world.meta, "scenarioId")

        val nations = nationRepository.findByWorldId(worldId).associateBy { it.id }
        val generals = generalRepository.findByWorldId(worldId).filter { it.userId != null && it.npcState.toInt() < 2 }

        for (general in generals) {
            val rank = asMap(general.meta["rank"])
            val warnum = readNumber(rank, "warnum")
            val killnum = readNumber(rank, "killnum")
            val firenum = readNumber(rank, "firenum")
            val killcrew = readNumber(rank, "killcrew")
            val deathcrew = readNumber(rank, "deathcrew")

            val hallValues = linkedMapOf(
                "experience" to general.experience.toDouble(),
                "dedication" to general.dedication.toDouble(),
                "warnum" to warnum.toDouble(),
                "killnum" to killnum.toDouble(),
                "firenum" to firenum.toDouble(),
                "winrate" to rate(killnum, warnum),
                "killrate" to rate(killcrew, deathcrew),
            )

            for ((type, value) in hallValues) {
                if ((type == "winrate" || type == "killrate") && warnum < 10) {
                    continue
                }
                if (value <= 0.0) {
                    continue
                }

                val nation = nations[general.nationId]
                val aux = mutableMapOf<String, Any>(
                    "name" to general.name,
                    "nationName" to (nation?.name ?: "재야"),
                    "bgColor" to (nation?.color ?: "#000000"),
                    "fgColor" to (nation?.color ?: "#000000"),
                    "picture" to general.picture,
                    "imgsvr" to general.imageServer,
                    "winnerNationId" to winnerNationId,
                )

                val existing = hallOfFameRepository.findByServerIdAndTypeAndGeneralNo(serverId, type, general.id)
                if (existing == null) {
                    hallOfFameRepository.save(
                        HallOfFame(
                            serverId = serverId,
                            season = season,
                            scenario = scenario,
                            generalNo = general.id,
                            type = type,
                            value = value,
                            owner = general.userId?.toString(),
                            aux = aux,
                        )
                    )
                } else if (value > existing.value) {
                    existing.value = value
                    existing.owner = general.userId?.toString()
                    existing.aux = aux
                    hallOfFameRepository.save(existing)
                }
            }
        }

        val gameHistory = gameHistoryRepository.findByServerId(serverId) ?: GameHistory(serverId = serverId)
        gameHistory.winnerNation = winnerNationId
        gameHistory.date = OffsetDateTime.now()
        gameHistoryRepository.save(gameHistory)
    }

    private fun settleDynasty(world: WorldState, winnerNationId: Long, winnerNationName: String) {
        val worldId = world.id.toLong()
        val serverId = readString(world.config, "serverId").ifBlank { world.name }
        val serverName = readString(world.config, "serverName").ifBlank { world.name }
        val nations = nationRepository.findByWorldId(worldId)
        val winnerNation = nations.firstOrNull { it.id == winnerNationId } ?: return
        val cities = cityRepository.findByWorldId(worldId)
        val generals = generalRepository.findByWorldId(worldId)

        val winnerGenerals = generals.filter { it.nationId == winnerNationId }
        val neutralGenerals = generals.filter { it.nationId == 0L }

        val ownedCities = cities.filter { it.nationId == winnerNationId }
        val popSum = ownedCities.sumOf { it.pop }
        val popMaxSum = ownedCities.sumOf { it.popMax }
        val popText = "$popSum / $popMaxSum"
        val popRate = if (popMaxSum > 0) "${(popSum.toDouble() / popMaxSum.toDouble() * 10000).toInt() / 100.0} %" else "0 %"

        val officerMap = mutableMapOf<Int, Pair<String, String>>()
        winnerGenerals
            .filter { it.officerLevel.toInt() >= 5 }
            .sortedByDescending { it.dedication }
            .forEach { general ->
                val level = general.officerLevel.toInt()
                if (!officerMap.containsKey(level)) {
                    officerMap[level] = general.name to general.picture
                }
            }

        val tiger = buildTopList(winnerGenerals, "killnum", 5)
        val eagle = buildTopList(winnerGenerals, "firenum", 7)
        val gen = winnerGenerals.sortedByDescending { it.dedication }.joinToString(", ") { it.name }

        val historyMessages = messageRepository
            .findByWorldIdAndMailboxCodeAndDestIdOrderBySentAtDesc(worldId, "nation_history", winnerNationId)
            .mapNotNull { it.payload["message"] as? String }
            .reversed()

        upsertOldNation(
            serverId = serverId,
            nationId = winnerNationId,
            data = mutableMapOf(
                "nation" to winnerNationId,
                "name" to winnerNation.name,
                "color" to winnerNation.color,
                "type" to winnerNation.typeCode,
                "level" to winnerNation.level,
                "gold" to winnerNation.gold,
                "rice" to winnerNation.rice,
                "power" to winnerNation.power,
                "capitalCityId" to (winnerNation.capitalCityId ?: 0),
                "generals" to winnerGenerals.map { it.id },
                "history" to historyMessages,
                "meta" to winnerNation.meta,
            )
        )

        upsertOldNation(
            serverId = serverId,
            nationId = 0,
            data = mutableMapOf(
                "nation" to 0,
                "name" to "재야",
                "color" to "#000000",
                "type" to "neutral",
                "level" to 0,
                "gold" to 0,
                "rice" to 0,
                "power" to 0,
                "capitalCityId" to 0,
                "generals" to neutralGenerals.map { it.id },
                "history" to emptyList<String>(),
                "meta" to mutableMapOf<String, Any>(),
            )
        )

        (winnerGenerals + neutralGenerals).forEach { general ->
            upsertOldGeneral(serverId, general, world)
        }

        val oldNations = oldNationRepository.findByServerId(serverId)
        val nationNameList = oldNations.mapNotNull { it.data["name"] as? String }.toMutableList()
        if (!nationNameList.contains(winnerNationName)) {
            nationNameList.add(winnerNationName)
        }

        val nationTypeCounts = mutableMapOf<String, Int>()
        oldNations.forEach { oldNation ->
            val type = oldNation.data["type"] as? String ?: return@forEach
            nationTypeCounts[type] = (nationTypeCounts[type] ?: 0) + 1
        }
        nationTypeCounts[winnerNation.typeCode] = (nationTypeCounts[winnerNation.typeCode] ?: 0) + 1
        val nationHist = nationTypeCounts.entries.joinToString(", ") { "${it.key}(${it.value})" }

        val serverCount = gameHistoryRepository.count() + 1

        emperorRepository.save(
            Emperor(
                serverId = serverId,
                phase = "${serverName}${serverCount}기",
                nationCount = "${nations.size} / ${nations.size}",
                nationName = nationNameList.joinToString(", "),
                nationHist = nationHist,
                genCount = "${generals.size} / ${generals.size}",
                personalHist = "",
                specialHist = "",
                name = winnerNation.name,
                type = winnerNation.typeCode,
                color = winnerNation.color,
                year = world.currentYear,
                month = world.currentMonth,
                power = winnerNation.power,
                gennum = winnerGenerals.size,
                citynum = ownedCities.size,
                pop = popText,
                poprate = popRate,
                gold = winnerNation.gold,
                rice = winnerNation.rice,
                l12name = officerMap[12]?.first ?: "",
                l12pic = officerMap[12]?.second ?: "",
                l11name = officerMap[11]?.first ?: "",
                l11pic = officerMap[11]?.second ?: "",
                l10name = officerMap[10]?.first ?: "",
                l10pic = officerMap[10]?.second ?: "",
                l9name = officerMap[9]?.first ?: "",
                l9pic = officerMap[9]?.second ?: "",
                l8name = officerMap[8]?.first ?: "",
                l8pic = officerMap[8]?.second ?: "",
                l7name = officerMap[7]?.first ?: "",
                l7pic = officerMap[7]?.second ?: "",
                l6name = officerMap[6]?.first ?: "",
                l6pic = officerMap[6]?.second ?: "",
                l5name = officerMap[5]?.first ?: "",
                l5pic = officerMap[5]?.second ?: "",
                tiger = tiger,
                eagle = eagle,
                gen = gen,
                history = historyMessages,
                aux = mutableMapOf<String, Any>(
                    "winnerNationId" to winnerNationId,
                ),
            )
        )
    }

    private fun upsertOldNation(serverId: String, nationId: Long, data: MutableMap<String, Any>) {
        val oldNation = oldNationRepository.findByServerIdAndNation(serverId, nationId) ?: OldNation(
            serverId = serverId,
            nation = nationId,
        )
        oldNation.data = data
        oldNation.date = OffsetDateTime.now()
        oldNationRepository.save(oldNation)
    }

    private fun upsertOldGeneral(serverId: String, general: General, world: WorldState) {
        val oldGeneral = oldGeneralRepository.findByServerIdAndGeneralNo(serverId, general.id) ?: OldGeneral(
            serverId = serverId,
            generalNo = general.id,
        )
        oldGeneral.owner = general.userId?.toString()
        oldGeneral.name = general.name
        oldGeneral.lastYearMonth = world.currentYear.toInt() * 100 + world.currentMonth.toInt()
        oldGeneral.turnTime = general.turnTime
        oldGeneral.data = mutableMapOf(
            "id" to general.id,
            "name" to general.name,
            "nationId" to general.nationId,
            "cityId" to general.cityId,
            "troopId" to general.troopId,
            "officerLevel" to general.officerLevel,
            "dedication" to general.dedication,
            "experience" to general.experience,
            "leadership" to general.leadership,
            "strength" to general.strength,
            "intel" to general.intel,
            "politics" to general.politics,
            "charm" to general.charm,
            "gold" to general.gold,
            "rice" to general.rice,
            "crew" to general.crew,
            "crewType" to general.crewType,
            "train" to general.train,
            "atmos" to general.atmos,
            "age" to general.age,
            "startAge" to general.startAge,
            "npcState" to general.npcState,
            "personalCode" to general.personalCode,
            "specialCode" to general.specialCode,
            "special2Code" to general.special2Code,
            "turnTime" to general.turnTime.toString(),
            "meta" to general.meta,
        )
        oldGeneralRepository.save(oldGeneral)
    }

    private fun buildTopList(generals: List<General>, rankKey: String, limit: Int): String {
        val rows = generals
            .map { general ->
                val rank = asMap(general.meta["rank"])
                val value = readNumber(rank, rankKey)
                general.name to value
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)

        return rows.joinToString(", ") { "${it.first}【${it.second}】" }
    }

    private fun computeDexPoint(general: General): Int {
        val dexMeta = asMap(general.meta["dex"])
        val total = general.dex1 + general.dex2 + general.dex3 + general.dex4 + general.dex5 +
            dexMeta.values.sumOf { (it as? Number)?.toInt() ?: 0 }
        return (total * 0.001).toInt()
    }

    private fun rate(numerator: Int, denominator: Int): Double {
        if (denominator <= 0) {
            return 0.0
        }
        return numerator.toDouble() / denominator.toDouble()
    }

    private fun readNumber(map: Map<String, Any>, key: String): Int {
        return (map[key] as? Number)?.toInt() ?: 0
    }

    private fun readString(map: Map<String, Any>, key: String): String {
        return map[key] as? String ?: ""
    }

    private fun asMap(value: Any?): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any> ?: emptyMap()
    }
}
