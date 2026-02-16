package com.opensam.dto

data class FrontInfoResponse(
    val global: GlobalInfo,
    val general: GeneralFrontInfo?,
    val nation: NationFrontInfo?,
    val city: CityFrontInfo?,
    val recentRecord: RecentRecordInfo,
    val aux: AuxInfo,
)

data class AuxInfo(
    val myLastVote: Long? = null,
)

data class GlobalInfo(
    val year: Int,
    val month: Int,
    val turnTerm: Int,
    val startyear: Int,
    val genCount: List<List<Int>>,
    val onlineNations: List<OnlineNationInfo>,
    val onlineUserCnt: Int,
    val auctionCount: Int,
    val tournamentState: Int,
    val tournamentType: Int?,
    val tournamentTime: String?,
    val isTournamentActive: Boolean,
    val isTournamentApplicationOpen: Boolean,
    val isBettingActive: Boolean,
    val lastExecuted: String?,
    val isLocked: Boolean,
    val scenarioText: String,
    val realtimeMode: Boolean,
    val extendedGeneral: Int,
    val isFiction: Int,
    val npcMode: Int,
    val joinMode: String,
    val develCost: Int,
    val noticeMsg: Int,
    val apiLimit: Int,
    val generalCntLimit: Int,
    val serverCnt: Int,
    val lastVoteID: Int,
    val lastVote: Any?,
)

data class OnlineNationInfo(
    val id: Long,
    val name: String,
    val color: String,
    val genCount: Int,
)

data class NationTypeInfo(
    val raw: String,
    val name: String,
    val pros: String,
    val cons: String,
)

data class NationPopulationInfo(
    val cityCnt: Int,
    val now: Int,
    val max: Int,
)

data class NationCrewInfo(
    val generalCnt: Int,
    val now: Int,
    val max: Int,
)

data class TopChiefInfo(
    val officerLevel: Int,
    val no: Long,
    val name: String,
    val npc: Int,
)

data class GeneralFrontInfo(
    val no: Long,
    val name: String,
    val picture: String,
    val imgsvr: Int,
    val nation: Long,
    val npc: Int,
    val city: Long,
    val troop: Long,
    val officerLevel: Int,
    val officerLevelText: String,
    val officerCity: Int,
    val permission: Int,
    val lbonus: Int,
    val leadership: Int,
    val leadershipExp: Int,
    val strength: Int,
    val strengthExp: Int,
    val intel: Int,
    val intelExp: Int,
    val politics: Int,
    val charm: Int,
    val experience: Int,
    val dedication: Int,
    val explevel: Int,
    val dedlevel: Int,
    val honorText: String,
    val dedLevelText: String,
    val bill: Int,
    val gold: Int,
    val rice: Int,
    val crew: Int,
    val crewtype: String,
    val train: Int,
    val atmos: Int,
    val weapon: String,
    val book: String,
    val horse: String,
    val item: String,
    val personal: String,
    val specialDomestic: String,
    val specialWar: String,
    val specage: Int,
    val specage2: Int,
    val age: Int,
    val injury: Int,
    val killturn: Int?,
    val belong: Int,
    val betray: Int,
    val blockState: Int,
    val defenceTrain: Int,
    val turntime: String,
    val recentWar: String?,
    val commandPoints: Int,
    val commandEndTime: String?,
    val ownerName: String?,
    val refreshScoreTotal: Int?,
    val refreshScore: Int?,
    val autorunLimit: Int,
    val reservedCommand: Any?,
    val troopInfo: TroopInfo?,
    val dex1: Int,
    val dex2: Int,
    val dex3: Int,
    val dex4: Int,
    val dex5: Int,
    val warnum: Int,
    val killnum: Int,
    val deathnum: Int,
    val killcrew: Int,
    val deathcrew: Int,
    val firenum: Int,
)

data class TroopInfo(
    val leader: TroopLeaderInfo,
    val name: String,
)

data class TroopLeaderInfo(
    val city: Long,
    val reservedCommand: Any?,
)

data class NationFrontInfo(
    val id: Long,
    val full: Boolean,
    val name: String,
    val color: String,
    val level: Int,
    val type: NationTypeInfo,
    val gold: Int,
    val rice: Int,
    val tech: Float,
    val power: Int,
    val gennum: Int,
    val capital: Long?,
    val bill: Int,
    val taxRate: Int,
    val population: NationPopulationInfo,
    val crew: NationCrewInfo,
    val onlineGen: String,
    val notice: NationNoticeInfo?,
    val topChiefs: Map<Int, TopChiefInfo?>,
    val diplomaticLimit: Int,
    val strategicCmdLimit: Int,
    val impossibleStrategicCommand: List<Any>,
    val prohibitScout: Int,
    val prohibitWar: Int,
)

data class NationNoticeInfo(
    val date: String,
    val msg: String,
    val author: String,
    val authorID: Long,
)

data class CityNationInfo(
    val id: Long,
    val name: String,
    val color: String,
)

data class CityFrontInfo(
    val id: Long,
    val name: String,
    val level: Int,
    val nationInfo: CityNationInfo,
    val trust: Int,
    val pop: List<Int>,
    val agri: List<Int>,
    val comm: List<Int>,
    val secu: List<Int>,
    val def: List<Int>,
    val wall: List<Int>,
    val trade: Int?,
    val officerList: Map<Int, CityOfficerInfo?>,
)

data class CityOfficerInfo(
    val officerLevel: Int,
    val name: String,
    val npc: Int,
)

data class RecentRecordInfo(
    val flushGeneral: Boolean,
    val flushGlobal: Boolean,
    val flushHistory: Boolean,
    val general: List<RecordEntry>,
    val global: List<RecordEntry>,
    val history: List<RecordEntry>,
)

data class RecordEntry(
    val id: Long,
    val message: String,
    val date: String,
)
