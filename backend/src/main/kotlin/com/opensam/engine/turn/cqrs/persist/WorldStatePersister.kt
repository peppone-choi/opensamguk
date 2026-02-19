package com.opensam.engine.turn.cqrs.persist

import com.opensam.engine.turn.cqrs.memory.DirtyTracker
import com.opensam.engine.turn.cqrs.memory.InMemoryWorldState
import com.opensam.entity.GeneralTurn
import com.opensam.entity.NationTurn
import com.opensam.repository.CityRepository
import com.opensam.repository.DiplomacyRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.GeneralTurnRepository
import com.opensam.repository.NationRepository
import com.opensam.repository.NationTurnRepository
import com.opensam.repository.TroopRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WorldStatePersister(
    private val generalRepository: GeneralRepository,
    private val cityRepository: CityRepository,
    private val nationRepository: NationRepository,
    private val troopRepository: TroopRepository,
    private val diplomacyRepository: DiplomacyRepository,
    private val generalTurnRepository: GeneralTurnRepository,
    private val nationTurnRepository: NationTurnRepository,
    private val jpaBulkWriter: JpaBulkWriter,
) {
    @Transactional
    fun persist(state: InMemoryWorldState, tracker: DirtyTracker, worldId: Long) {
        val changes = tracker.consumeAll()

        val generalUpserts = (changes.dirtyGeneralIds + changes.createdGeneralIds)
            .mapNotNull { id ->
                val snapshot = state.generals[id] ?: return@mapNotNull null
                generalRepository.findById(id).orElse(null)?.also { entity ->
                    entity.worldId = worldId
                    entity.userId = snapshot.userId
                    entity.name = snapshot.name
                    entity.nationId = snapshot.nationId
                    entity.cityId = snapshot.cityId
                    entity.troopId = snapshot.troopId
                    entity.npcState = snapshot.npcState
                    entity.npcOrg = snapshot.npcOrg
                    entity.affinity = snapshot.affinity
                    entity.bornYear = snapshot.bornYear
                    entity.deadYear = snapshot.deadYear
                    entity.picture = snapshot.picture
                    entity.imageServer = snapshot.imageServer
                    entity.leadership = snapshot.leadership
                    entity.leadershipExp = snapshot.leadershipExp
                    entity.strength = snapshot.strength
                    entity.strengthExp = snapshot.strengthExp
                    entity.intel = snapshot.intel
                    entity.intelExp = snapshot.intelExp
                    entity.politics = snapshot.politics
                    entity.charm = snapshot.charm
                    entity.dex1 = snapshot.dex1
                    entity.dex2 = snapshot.dex2
                    entity.dex3 = snapshot.dex3
                    entity.dex4 = snapshot.dex4
                    entity.dex5 = snapshot.dex5
                    entity.injury = snapshot.injury
                    entity.experience = snapshot.experience
                    entity.dedication = snapshot.dedication
                    entity.officerLevel = snapshot.officerLevel
                    entity.officerCity = snapshot.officerCity
                    entity.permission = snapshot.permission
                    entity.gold = snapshot.gold
                    entity.rice = snapshot.rice
                    entity.crew = snapshot.crew
                    entity.crewType = snapshot.crewType
                    entity.train = snapshot.train
                    entity.atmos = snapshot.atmos
                    entity.weaponCode = snapshot.weaponCode
                    entity.bookCode = snapshot.bookCode
                    entity.horseCode = snapshot.horseCode
                    entity.itemCode = snapshot.itemCode
                    entity.ownerName = snapshot.ownerName
                    entity.newmsg = snapshot.newmsg
                    entity.turnTime = snapshot.turnTime
                    entity.recentWarTime = snapshot.recentWarTime
                    entity.makeLimit = snapshot.makeLimit
                    entity.killTurn = snapshot.killTurn
                    entity.blockState = snapshot.blockState
                    entity.dedLevel = snapshot.dedLevel
                    entity.expLevel = snapshot.expLevel
                    entity.age = snapshot.age
                    entity.startAge = snapshot.startAge
                    entity.belong = snapshot.belong
                    entity.betray = snapshot.betray
                    entity.personalCode = snapshot.personalCode
                    entity.specialCode = snapshot.specialCode
                    entity.specAge = snapshot.specAge
                    entity.special2Code = snapshot.special2Code
                    entity.spec2Age = snapshot.spec2Age
                    entity.defenceTrain = snapshot.defenceTrain
                    entity.tournamentState = snapshot.tournamentState
                    entity.commandPoints = snapshot.commandPoints
                    entity.commandEndTime = snapshot.commandEndTime
                    entity.lastTurn = snapshot.lastTurn.toMutableMap()
                    entity.meta = snapshot.meta.toMutableMap()
                    entity.penalty = snapshot.penalty.toMutableMap()
                    entity.createdAt = snapshot.createdAt
                    entity.updatedAt = snapshot.updatedAt
                }
            }
        jpaBulkWriter.saveAll(generalRepository, generalUpserts)
        jpaBulkWriter.deleteAllById(generalRepository, changes.deletedGeneralIds)

        val cityUpserts = (changes.dirtyCityIds + changes.createdCityIds)
            .mapNotNull { id ->
                val snapshot = state.cities[id] ?: return@mapNotNull null
                cityRepository.findById(id).orElse(null)?.also { entity ->
                    entity.worldId = worldId
                    entity.name = snapshot.name
                    entity.level = snapshot.level
                    entity.nationId = snapshot.nationId
                    entity.supplyState = snapshot.supplyState
                    entity.frontState = snapshot.frontState
                    entity.pop = snapshot.pop
                    entity.popMax = snapshot.popMax
                    entity.agri = snapshot.agri
                    entity.agriMax = snapshot.agriMax
                    entity.comm = snapshot.comm
                    entity.commMax = snapshot.commMax
                    entity.secu = snapshot.secu
                    entity.secuMax = snapshot.secuMax
                    entity.trust = snapshot.trust
                    entity.trade = snapshot.trade
                    entity.dead = snapshot.dead
                    entity.def = snapshot.def
                    entity.defMax = snapshot.defMax
                    entity.wall = snapshot.wall
                    entity.wallMax = snapshot.wallMax
                    entity.officerSet = snapshot.officerSet
                    entity.state = snapshot.state
                    entity.region = snapshot.region
                    entity.term = snapshot.term
                    entity.conflict = snapshot.conflict.toMutableMap()
                    entity.meta = snapshot.meta.toMutableMap()
                }
            }
        jpaBulkWriter.saveAll(cityRepository, cityUpserts)
        jpaBulkWriter.deleteAllById(cityRepository, changes.deletedCityIds)

        val nationUpserts = (changes.dirtyNationIds + changes.createdNationIds)
            .mapNotNull { id ->
                val snapshot = state.nations[id] ?: return@mapNotNull null
                nationRepository.findById(id).orElse(null)?.also { entity ->
                    entity.worldId = worldId
                    entity.name = snapshot.name
                    entity.color = snapshot.color
                    entity.capitalCityId = snapshot.capitalCityId
                    entity.gold = snapshot.gold
                    entity.rice = snapshot.rice
                    entity.bill = snapshot.bill
                    entity.rate = snapshot.rate
                    entity.rateTmp = snapshot.rateTmp
                    entity.secretLimit = snapshot.secretLimit
                    entity.chiefGeneralId = snapshot.chiefGeneralId
                    entity.scoutLevel = snapshot.scoutLevel
                    entity.warState = snapshot.warState
                    entity.strategicCmdLimit = snapshot.strategicCmdLimit
                    entity.surrenderLimit = snapshot.surrenderLimit
                    entity.tech = snapshot.tech
                    entity.power = snapshot.power
                    entity.level = snapshot.level
                    entity.typeCode = snapshot.typeCode
                    entity.spy = snapshot.spy.toMutableMap()
                    entity.meta = snapshot.meta.toMutableMap()
                    entity.createdAt = snapshot.createdAt
                    entity.updatedAt = snapshot.updatedAt
                }
            }
        jpaBulkWriter.saveAll(nationRepository, nationUpserts)
        jpaBulkWriter.deleteAllById(nationRepository, changes.deletedNationIds)

        val troopUpserts = (changes.dirtyTroopIds + changes.createdTroopIds)
            .mapNotNull { id ->
                val snapshot = state.troops[id] ?: return@mapNotNull null
                troopRepository.findById(id).orElse(null)?.also { entity ->
                    entity.worldId = worldId
                    entity.leaderGeneralId = snapshot.leaderGeneralId
                    entity.nationId = snapshot.nationId
                    entity.name = snapshot.name
                    entity.meta = snapshot.meta.toMutableMap()
                    entity.createdAt = snapshot.createdAt
                }
            }
        jpaBulkWriter.saveAll(troopRepository, troopUpserts)
        jpaBulkWriter.deleteAllById(troopRepository, changes.deletedTroopIds)

        val diplomacyUpserts = (changes.dirtyDiplomacyIds + changes.createdDiplomacyIds)
            .mapNotNull { id ->
                val snapshot = state.diplomacies[id] ?: return@mapNotNull null
                diplomacyRepository.findById(id).orElse(null)?.also { entity ->
                    entity.worldId = worldId
                    entity.srcNationId = snapshot.srcNationId
                    entity.destNationId = snapshot.destNationId
                    entity.stateCode = snapshot.stateCode
                    entity.term = snapshot.term
                    entity.isDead = snapshot.isDead
                    entity.isShowing = snapshot.isShowing
                    entity.meta = snapshot.meta.toMutableMap()
                    entity.createdAt = snapshot.createdAt
                }
            }
        jpaBulkWriter.saveAll(diplomacyRepository, diplomacyUpserts)
        jpaBulkWriter.deleteAllById(diplomacyRepository, changes.deletedDiplomacyIds)

        generalTurnRepository.deleteByWorldId(worldId)
        val generalTurns = state.generalTurnsByGeneralId
            .values
            .flatten()
            .sortedWith(compareBy({ it.generalId }, { it.turnIdx }))
            .map {
                GeneralTurn(
                    id = 0,
                    worldId = worldId,
                    generalId = it.generalId,
                    turnIdx = it.turnIdx,
                    actionCode = it.actionCode,
                    arg = it.arg.toMutableMap(),
                    brief = it.brief,
                    createdAt = it.createdAt,
                )
            }
        jpaBulkWriter.saveAll(generalTurnRepository, generalTurns)

        nationTurnRepository.deleteByWorldId(worldId)
        val nationTurns = state.nationTurnsByNationAndLevel
            .values
            .flatten()
            .sortedWith(compareBy({ it.nationId }, { it.officerLevel }, { it.turnIdx }))
            .map {
                NationTurn(
                    id = 0,
                    worldId = worldId,
                    nationId = it.nationId,
                    officerLevel = it.officerLevel,
                    turnIdx = it.turnIdx,
                    actionCode = it.actionCode,
                    arg = it.arg.toMutableMap(),
                    brief = it.brief,
                    createdAt = it.createdAt,
                )
            }
        jpaBulkWriter.saveAll(nationTurnRepository, nationTurns)
    }
}
