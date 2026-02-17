package com.opensam.service

import com.opensam.dto.*
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Message
import com.opensam.entity.Nation
import com.opensam.repository.*
import org.springframework.stereotype.Service
import kotlin.math.ceil
import kotlin.math.sqrt

@Service
class FrontInfoService(
    private val worldStateRepository: WorldStateRepository,
    private val generalRepository: GeneralRepository,
    private val nationRepository: NationRepository,
    private val cityRepository: CityRepository,
    private val messageRepository: MessageRepository,
    private val appUserRepository: AppUserRepository,
    private val troopRepository: TroopRepository,
    private val officerRankService: OfficerRankService,
) {
    companion object {
        private const val MAX_DED_LEVEL = 10
    }

    fun getFrontInfo(worldId: Long, loginId: String, lastRecordId: Long?, lastHistoryId: Long?): FrontInfoResponse {
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null)
            ?: throw IllegalArgumentException("World not found: $worldId")

        val allGenerals = generalRepository.findByWorldId(worldId)
        val userId = appUserRepository.findByLoginId(loginId)?.id
        val myGeneral = userId?.let { uid -> allGenerals.find { it.userId == uid } }

        val nations = nationRepository.findByWorldId(worldId)
        val nationsById = nations.associateBy { it.id }
        val nation = myGeneral?.let { nationsById[it.nationId] }
        val city = myGeneral?.let { cityRepository.findById(it.cityId).orElse(null) }

        val nationGenerals = allGenerals.groupBy { it.nationId }
        val onlineNations = nations.filter { nationGenerals.containsKey(it.id) }.map { n ->
            OnlineNationInfo(
                id = n.id,
                name = n.name,
                color = n.color,
                genCount = nationGenerals[n.id]?.size ?: 0,
            )
        }

        // genCount by npcState: [[npcState, count], ...]
        val genCountByNpc = allGenerals.groupBy { it.npcState.toInt() }
            .map { (npc, gens) -> listOf(npc, gens.size) }

        val tournamentState = (world.meta["tournamentState"] as? Number)?.toInt() ?: 0

        val globalInfo = GlobalInfo(
            year = world.currentYear.toInt(),
            month = world.currentMonth.toInt(),
            turnTerm = world.tickSeconds / 60,
            startyear = (world.config["startyear"] as? Number)?.toInt() ?: world.currentYear.toInt(),
            genCount = genCountByNpc,
            onlineNations = onlineNations,
            onlineUserCnt = allGenerals.count { it.userId != null },
            auctionCount = 0,
            tournamentState = tournamentState,
            tournamentType = (world.meta["tournamentType"] as? Number)?.toInt(),
            tournamentTime = world.meta["tournamentTime"] as? String,
            isTournamentActive = tournamentState > 0,
            isTournamentApplicationOpen = tournamentState == 1,
            isBettingActive = tournamentState == 6,
            lastExecuted = world.meta["lastExecuted"] as? String,
            isLocked = (world.config["locked"] as? Boolean) ?: false,
            scenarioText = world.scenarioCode,
            realtimeMode = world.realtimeMode,
            extendedGeneral = (world.config["extendedGeneral"] as? Number)?.toInt() ?: 0,
            isFiction = (world.config["isFiction"] as? Number)?.toInt() ?: 0,
            npcMode = (world.config["npcMode"] as? Number)?.toInt() ?: 0,
            joinMode = (world.config["joinMode"] as? String) ?: "full",
            develCost = (world.config["develCost"] as? Number)?.toInt() ?: 0,
            noticeMsg = (world.config["noticeMsg"] as? Number)?.toInt() ?: 0,
            apiLimit = (world.config["refreshLimit"] as? Number)?.toInt() ?: 100,
            generalCntLimit = (world.config["maxGeneral"] as? Number)?.toInt() ?: 500,
            serverCnt = (world.config["serverCnt"] as? Number)?.toInt() ?: 1,
            lastVoteID = (world.meta["lastVoteID"] as? Number)?.toInt() ?: 0,
            lastVote = null,
        )

        val nationLevel = nation?.level?.toInt() ?: 0
        val generalInfo = myGeneral?.let { toGeneralFrontInfo(it, nationLevel, allGenerals) }

        val allCities = cityRepository.findByWorldId(worldId)
        val nationInfo = if (myGeneral != null && nation != null && nation.id > 0) {
            buildNationInfo(nation, nationGenerals, allCities, allGenerals)
        } else {
            buildDummyNationInfo()
        }

        val cityInfo = if (city != null && nation != null) {
            buildCityInfo(city, nation, nationsById, allGenerals)
        } else if (city != null) {
            buildCityInfo(city, null, nationsById, allGenerals)
        } else null

        val recentRecord = buildRecentRecord(worldId, myGeneral, lastRecordId, lastHistoryId)

        return FrontInfoResponse(
            global = globalInfo,
            general = generalInfo,
            nation = nationInfo,
            city = cityInfo,
            recentRecord = recentRecord,
            aux = AuxInfo(),
        )
    }

    private fun buildNationInfo(
        n: Nation,
        nationGenerals: Map<Long, List<General>>,
        allCities: List<City>,
        allGenerals: List<General>,
    ): NationFrontInfo {
        val myNationCities = allCities.filter { it.nationId == n.id }
        val myNationGens = nationGenerals[n.id] ?: emptyList()

        val populationNow = myNationCities.sumOf { it.pop }
        val populationMax = myNationCities.sumOf { it.popMax }

        val crewNow = myNationGens.filter { it.npcState.toInt() != 5 }.sumOf { it.crew }
        val crewMax = myNationGens.filter { it.npcState.toInt() != 5 }.sumOf { it.leadership.toInt() * 100 }

        val topChiefs = mutableMapOf<Int, TopChiefInfo?>()
        topChiefs[12] = null
        topChiefs[11] = null
        myNationGens.filter { it.officerLevel >= 11 }.forEach { g ->
            topChiefs[g.officerLevel.toInt()] = TopChiefInfo(
                officerLevel = g.officerLevel.toInt(),
                no = g.id,
                name = g.name,
                npc = g.npcState.toInt(),
            )
        }

        // Nation notice from meta
        @Suppress("UNCHECKED_CAST")
        val noticeMap = n.meta["nationNotice"] as? Map<String, Any>
        val notice = noticeMap?.let {
            NationNoticeInfo(
                date = (it["date"] as? String) ?: "",
                msg = (it["msg"] as? String) ?: "",
                author = (it["author"] as? String) ?: "",
                authorID = (it["authorID"] as? Number)?.toLong() ?: 0,
            )
        }

        // Online generals text
        val onlineGenNames = myNationGens
            .filter { it.userId != null }
            .joinToString(", ") { it.name }

        return NationFrontInfo(
            id = n.id,
            full = true,
            name = n.name,
            color = n.color,
            level = n.level.toInt(),
            type = NationTypeInfo(
                raw = n.typeCode,
                name = resolveNationTypeName(n.typeCode),
                pros = resolveNationTypePros(n.typeCode),
                cons = resolveNationTypeCons(n.typeCode),
            ),
            gold = n.gold,
            rice = n.rice,
            tech = n.tech,
            power = n.power,
            gennum = myNationGens.size,
            capital = n.capitalCityId,
            bill = n.bill.toInt(),
            taxRate = n.rate.toInt(),
            population = NationPopulationInfo(
                cityCnt = myNationCities.size,
                now = populationNow,
                max = populationMax,
            ),
            crew = NationCrewInfo(
                generalCnt = myNationGens.size,
                now = crewNow,
                max = crewMax,
            ),
            onlineGen = onlineGenNames,
            notice = notice,
            topChiefs = topChiefs,
            diplomaticLimit = n.surrenderLimit.toInt(),
            strategicCmdLimit = n.strategicCmdLimit.toInt(),
            impossibleStrategicCommand = emptyList(),
            prohibitScout = n.scoutLevel.toInt(),
            prohibitWar = n.warState.toInt(),
        )
    }

    private fun buildDummyNationInfo(): NationFrontInfo {
        return NationFrontInfo(
            id = 0,
            full = false,
            name = "재야",
            color = "#000000",
            level = 0,
            type = NationTypeInfo(raw = "None", name = "-", pros = "", cons = ""),
            gold = 0,
            rice = 0,
            tech = 0f,
            power = 0,
            gennum = 0,
            capital = null,
            bill = 0,
            taxRate = 0,
            population = NationPopulationInfo(cityCnt = 0, now = 0, max = 0),
            crew = NationCrewInfo(generalCnt = 0, now = 0, max = 0),
            onlineGen = "",
            notice = null,
            topChiefs = mapOf(12 to null, 11 to null),
            diplomaticLimit = 0,
            strategicCmdLimit = 0,
            impossibleStrategicCommand = emptyList(),
            prohibitScout = 0,
            prohibitWar = 0,
        )
    }

    private fun buildCityInfo(
        c: City,
        myNation: Nation?,
        nationsById: Map<Long, Nation>,
        allGenerals: List<General>,
    ): CityFrontInfo {
        val cityNation = nationsById[c.nationId]
        val nationInfo = CityNationInfo(
            id = c.nationId,
            name = cityNation?.name ?: "공백지",
            color = cityNation?.color ?: "#000000",
        )

        // Officer list keyed by level (4=태수, 3=군사, 2=종사)
        val officerList = mutableMapOf<Int, CityOfficerInfo?>(4 to null, 3 to null, 2 to null)
        allGenerals
            .filter { it.officerCity == c.id.toInt() && it.officerLevel.toInt() in listOf(2, 3, 4) }
            .forEach { g ->
                officerList[g.officerLevel.toInt()] = CityOfficerInfo(
                    officerLevel = g.officerLevel.toInt(),
                    name = g.name,
                    npc = g.npcState.toInt(),
                )
            }

        return CityFrontInfo(
            id = c.id,
            name = c.name,
            level = c.level.toInt(),
            nationInfo = nationInfo,
            trust = c.trust.toInt(),
            pop = listOf(c.pop, c.popMax),
            agri = listOf(c.agri, c.agriMax),
            comm = listOf(c.comm, c.commMax),
            secu = listOf(c.secu, c.secuMax),
            def = listOf(c.def, c.defMax),
            wall = listOf(c.wall, c.wallMax),
            trade = if (c.trade > 0) c.trade else null,
            officerList = officerList,
        )
    }

    private fun toGeneralFrontInfo(g: General, nationLevel: Int, allGenerals: List<General>): GeneralFrontInfo {
        val officerLevel = g.officerLevel.toInt()
        val dedLevel = calcDedLevel(g.dedication)

        // Permission level (numeric, like legacy)
        val permission = calcPermission(g)

        // Troop info
        val troopInfo = if (g.troopId > 0) {
            val troops = troopRepository.findByNationId(g.nationId)
            val troop = troops.find { it.leaderGeneralId == g.troopId }
            if (troop != null) {
                val leader = allGenerals.find { it.id == g.troopId }
                TroopInfo(
                    leader = TroopLeaderInfo(
                        city = leader?.cityId ?: 0,
                        reservedCommand = null,
                    ),
                    name = troop.name,
                )
            } else null
        } else null

        // Rank stats from meta
        val rankMeta = g.meta["rank"]
        @Suppress("UNCHECKED_CAST")
        val rank = rankMeta as? Map<String, Any> ?: emptyMap()

        // Dex from meta
        val dexMeta = g.meta["dex"]
        @Suppress("UNCHECKED_CAST")
        val dex = dexMeta as? Map<String, Any> ?: emptyMap()

        return GeneralFrontInfo(
            no = g.id,
            name = g.name,
            picture = g.picture,
            imgsvr = g.imageServer.toInt(),
            nation = g.nationId,
            npc = g.npcState.toInt(),
            city = g.cityId,
            troop = g.troopId,
            officerLevel = officerLevel,
            officerLevelText = officerRankService.getRankTitle(officerLevel, nationLevel),
            officerCity = g.officerCity,
            permission = permission,
            lbonus = calcLeadershipBonus(officerLevel, nationLevel),
            leadership = g.leadership.toInt(),
            leadershipExp = g.leadershipExp.toInt(),
            strength = g.strength.toInt(),
            strengthExp = g.strengthExp.toInt(),
            intel = g.intel.toInt(),
            intelExp = g.intelExp.toInt(),
            politics = g.politics.toInt(),
            charm = g.charm.toInt(),
            experience = g.experience,
            dedication = g.dedication,
            explevel = g.expLevel.toInt(),
            dedlevel = dedLevel,
            honorText = getHonorText(g.experience),
            dedLevelText = getDedLevelText(dedLevel),
            bill = getBillByLevel(dedLevel),
            gold = g.gold,
            rice = g.rice,
            crew = g.crew,
            crewtype = "che_${g.crewType}",
            train = g.train.toInt(),
            atmos = g.atmos.toInt(),
            weapon = g.weaponCode,
            book = g.bookCode,
            horse = g.horseCode,
            item = g.itemCode,
            personal = g.personalCode,
            specialDomestic = g.specialCode,
            specialWar = g.special2Code,
            specage = g.specAge.toInt(),
            specage2 = g.spec2Age.toInt(),
            age = g.age.toInt(),
            injury = g.injury.toInt(),
            killturn = g.killTurn?.toInt(),
            belong = g.belong.toInt(),
            betray = g.betray.toInt(),
            blockState = g.blockState.toInt(),
            defenceTrain = g.defenceTrain.toInt(),
            turntime = g.turnTime.toString(),
            recentWar = g.recentWarTime?.toString(),
            commandPoints = g.commandPoints,
            commandEndTime = g.commandEndTime?.toString(),
            ownerName = null,
            refreshScoreTotal = null,
            refreshScore = null,
            autorunLimit = (g.meta["autorun_limit"] as? Number)?.toInt() ?: 0,
            reservedCommand = null,
            troopInfo = troopInfo,
            dex1 = (dex["1"] as? Number)?.toInt() ?: 0,
            dex2 = (dex["2"] as? Number)?.toInt() ?: 0,
            dex3 = (dex["3"] as? Number)?.toInt() ?: 0,
            dex4 = (dex["4"] as? Number)?.toInt() ?: 0,
            dex5 = (dex["5"] as? Number)?.toInt() ?: 0,
            warnum = (rank["warnum"] as? Number)?.toInt() ?: 0,
            killnum = (rank["killnum"] as? Number)?.toInt() ?: 0,
            deathnum = (rank["deathnum"] as? Number)?.toInt() ?: 0,
            killcrew = (rank["killcrew"] as? Number)?.toInt() ?: 0,
            deathcrew = (rank["deathcrew"] as? Number)?.toInt() ?: 0,
            firenum = (rank["firenum"] as? Number)?.toInt() ?: 0,
        )
    }

    private fun calcPermission(g: General): Int {
        if (g.nationId <= 0) return -1
        if (g.officerLevel.toInt() == 0) return -1
        if (g.officerLevel.toInt() == 12) return 4
        if (g.permission == "ambassador") return 4
        if (g.permission == "auditor") return 3
        if (g.officerLevel >= 5) return 2
        if (g.officerLevel > 1) return 1
        return 0
    }

    private fun calcLeadershipBonus(officerLevel: Int, nationLevel: Int): Int {
        return when {
            officerLevel == 12 -> nationLevel * 2
            officerLevel >= 5 -> nationLevel
            else -> 0
        }
    }

    private fun calcDedLevel(dedication: Int): Int {
        return ceil(sqrt(dedication.toDouble()) / 10).toInt().coerceIn(0, MAX_DED_LEVEL)
    }

    private fun getDedLevelText(dedLevel: Int): String {
        if (dedLevel == 0) return "무품관"
        val invLevel = MAX_DED_LEVEL - dedLevel + 1
        return "${invLevel}품관"
    }

    private fun getBillByLevel(dedLevel: Int): Int {
        return dedLevel * 200 + 400
    }

    private fun getHonorText(experience: Int): String {
        return when {
            experience < 640 -> "전무"
            experience < 2560 -> "무명"
            experience < 5760 -> "신동"
            experience < 10240 -> "약간"
            experience < 16000 -> "평범"
            experience < 23040 -> "지역적"
            experience < 31360 -> "전국적"
            experience < 40960 -> "세계적"
            experience < 45000 -> "유명"
            experience < 51840 -> "명사"
            experience < 55000 -> "호걸"
            experience < 64000 -> "효웅"
            experience < 77440 -> "영웅"
            else -> "구세주"
        }
    }

    private fun resolveNationTypeName(typeCode: String): String {
        val suffix = typeCode.substringAfter("_", "")
        return suffix.ifEmpty { typeCode }
    }

    private val NATION_TYPE_PROS_CONS = mapOf(
        "che_중립" to ("" to ""),
        "che_군벌" to ("전투↑" to "내정↓"),
        "che_왕도" to ("내정↑ 인구↑" to ""),
        "che_패도" to ("전투↑" to "내정↓"),
        "che_상인" to ("금수입↑" to ""),
        "che_농업국" to ("쌀수입↑ 인구↑" to ""),
        "che_유목" to ("전투↑ 회피↑" to ""),
        "che_해적" to ("필살↑ 전투↑" to ""),
        "che_황건" to ("내정비용↓ 계략↑" to ""),
        "che_종교" to ("성공률↑ 인구↑" to ""),
        "che_학문" to ("지력↑ 내정↑" to ""),
        "che_명문" to ("내정↑ 전투↑" to ""),
        "che_의적" to ("필살↑ 내정↑" to ""),
        "che_은둔" to ("회피↑" to ""),
        "che_무사" to ("무력↑ 전투↑" to ""),
        "che_건국" to ("내정↑" to ""),
        // Legacy PHP nation types
        "che_유가" to ("농상↑ 민심↑" to "쌀수입↓"),
        "che_음양가" to ("농상↑ 인구↑" to "기술↓ 전략↓"),
        "che_명가" to ("기술↑ 인구↑" to "쌀수입↓ 수성↓"),
        "che_태평도" to ("인구↑ 민심↑" to "기술↓ 수성↓"),
        "che_병가" to ("기술↑ 수성↑" to "인구↓ 민심↓"),
        "che_도적" to ("계략↑" to "금수입↓ 치안↓ 민심↓"),
        "che_도가" to ("인구↑" to "기술↓ 치안↓"),
        "che_불가" to ("민심↑ 수성↑" to "금수입↓"),
        "che_묵가" to ("수성↑" to "기술↓"),
        "che_법가" to ("금수입↑ 치안↑" to "인구↓ 민심↓"),
        "che_종횡가" to ("전략↑ 수성↑" to "금수입↓ 농상↓"),
        "che_덕가" to ("치안↑ 인구↑ 민심↑" to "쌀수입↓ 수성↓"),
        "che_오두미도" to ("쌀수입↑ 인구↑" to "기술↓ 수성↓ 농상↓"),
    )

    private fun resolveNationTypePros(typeCode: String): String {
        return NATION_TYPE_PROS_CONS[typeCode]?.first ?: ""
    }

    private fun resolveNationTypeCons(typeCode: String): String {
        return NATION_TYPE_PROS_CONS[typeCode]?.second ?: ""
    }

    private fun buildRecentRecord(worldId: Long, myGeneral: General?, lastRecordId: Long?, lastHistoryId: Long?): RecentRecordInfo {
        val generalRecords = if (myGeneral != null) {
            val sinceId = lastRecordId ?: 0
            messageRepository.findByWorldIdAndMailboxCodeAndIdGreaterThanOrderBySentAtDesc(
                worldId, "general_record", sinceId
            ).filter { it.destId == myGeneral.id }
        } else emptyList()

        val globalRecords = messageRepository.findByWorldIdAndMailboxCodeAndIdGreaterThanOrderBySentAtDesc(
            worldId, "world_record", lastRecordId ?: 0
        )

        val historyRecords = messageRepository.findByWorldIdAndMailboxCodeAndIdGreaterThanOrderBySentAtDesc(
            worldId, "world_history", lastHistoryId ?: 0
        )

        return RecentRecordInfo(
            flushGeneral = generalRecords.isNotEmpty(),
            flushGlobal = globalRecords.isNotEmpty(),
            flushHistory = historyRecords.isNotEmpty(),
            general = generalRecords.map { toRecordEntry(it) },
            global = globalRecords.map { toRecordEntry(it) },
            history = historyRecords.map { toRecordEntry(it) },
        )
    }

    private fun toRecordEntry(m: Message) = RecordEntry(
        id = m.id,
        message = (m.payload["message"] as? String) ?: "",
        date = m.sentAt.toString(),
    )
}
