package com.opensam.engine.turn.cqrs.memory

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
class WorldStateLoader(
    private val generalRepository: GeneralRepository,
    private val cityRepository: CityRepository,
    private val nationRepository: NationRepository,
    private val troopRepository: TroopRepository,
    private val diplomacyRepository: DiplomacyRepository,
    private val generalTurnRepository: GeneralTurnRepository,
    private val nationTurnRepository: NationTurnRepository,
) {
    @Transactional(readOnly = true)
    fun loadWorldState(worldId: Long): InMemoryWorldState {
        val state = InMemoryWorldState(worldId = worldId)

        generalRepository.findByWorldId(worldId).forEach { general ->
            state.generals[general.id] = GeneralSnapshot(
                id = general.id,
                worldId = general.worldId,
                userId = general.userId,
                name = general.name,
                nationId = general.nationId,
                cityId = general.cityId,
                troopId = general.troopId,
                npcState = general.npcState,
                npcOrg = general.npcOrg,
                affinity = general.affinity,
                bornYear = general.bornYear,
                deadYear = general.deadYear,
                picture = general.picture,
                imageServer = general.imageServer,
                leadership = general.leadership,
                leadershipExp = general.leadershipExp,
                strength = general.strength,
                strengthExp = general.strengthExp,
                intel = general.intel,
                intelExp = general.intelExp,
                politics = general.politics,
                charm = general.charm,
                dex1 = general.dex1,
                dex2 = general.dex2,
                dex3 = general.dex3,
                dex4 = general.dex4,
                dex5 = general.dex5,
                injury = general.injury,
                experience = general.experience,
                dedication = general.dedication,
                officerLevel = general.officerLevel,
                officerCity = general.officerCity,
                permission = general.permission,
                gold = general.gold,
                rice = general.rice,
                crew = general.crew,
                crewType = general.crewType,
                train = general.train,
                atmos = general.atmos,
                weaponCode = general.weaponCode,
                bookCode = general.bookCode,
                horseCode = general.horseCode,
                itemCode = general.itemCode,
                ownerName = general.ownerName,
                newmsg = general.newmsg,
                turnTime = general.turnTime,
                recentWarTime = general.recentWarTime,
                makeLimit = general.makeLimit,
                killTurn = general.killTurn,
                blockState = general.blockState,
                dedLevel = general.dedLevel,
                expLevel = general.expLevel,
                age = general.age,
                startAge = general.startAge,
                belong = general.belong,
                betray = general.betray,
                personalCode = general.personalCode,
                specialCode = general.specialCode,
                specAge = general.specAge,
                special2Code = general.special2Code,
                spec2Age = general.spec2Age,
                defenceTrain = general.defenceTrain,
                tournamentState = general.tournamentState,
                commandPoints = general.commandPoints,
                commandEndTime = general.commandEndTime,
                lastTurn = general.lastTurn.toMutableMap(),
                meta = general.meta.toMutableMap(),
                penalty = general.penalty.toMutableMap(),
                createdAt = general.createdAt,
                updatedAt = general.updatedAt,
            )
        }

        cityRepository.findByWorldId(worldId).forEach { city ->
            state.cities[city.id] = CitySnapshot(
                id = city.id,
                worldId = city.worldId,
                name = city.name,
                level = city.level,
                nationId = city.nationId,
                supplyState = city.supplyState,
                frontState = city.frontState,
                pop = city.pop,
                popMax = city.popMax,
                agri = city.agri,
                agriMax = city.agriMax,
                comm = city.comm,
                commMax = city.commMax,
                secu = city.secu,
                secuMax = city.secuMax,
                trust = city.trust,
                trade = city.trade,
                dead = city.dead,
                def = city.def,
                defMax = city.defMax,
                wall = city.wall,
                wallMax = city.wallMax,
                officerSet = city.officerSet,
                state = city.state,
                region = city.region,
                term = city.term,
                conflict = city.conflict.toMutableMap(),
                meta = city.meta.toMutableMap(),
            )
        }

        nationRepository.findByWorldId(worldId).forEach { nation ->
            state.nations[nation.id] = NationSnapshot(
                id = nation.id,
                worldId = nation.worldId,
                name = nation.name,
                color = nation.color,
                capitalCityId = nation.capitalCityId,
                gold = nation.gold,
                rice = nation.rice,
                bill = nation.bill,
                rate = nation.rate,
                rateTmp = nation.rateTmp,
                secretLimit = nation.secretLimit,
                chiefGeneralId = nation.chiefGeneralId,
                scoutLevel = nation.scoutLevel,
                warState = nation.warState,
                strategicCmdLimit = nation.strategicCmdLimit,
                surrenderLimit = nation.surrenderLimit,
                tech = nation.tech,
                power = nation.power,
                level = nation.level,
                typeCode = nation.typeCode,
                spy = nation.spy.toMutableMap(),
                meta = nation.meta.toMutableMap(),
                createdAt = nation.createdAt,
                updatedAt = nation.updatedAt,
            )
        }

        troopRepository.findByWorldId(worldId).forEach { troop ->
            state.troops[troop.id] = TroopSnapshot(
                id = troop.id,
                worldId = troop.worldId,
                leaderGeneralId = troop.leaderGeneralId,
                nationId = troop.nationId,
                name = troop.name,
                meta = troop.meta.toMutableMap(),
                createdAt = troop.createdAt,
            )
        }

        diplomacyRepository.findByWorldId(worldId).forEach { diplomacy ->
            state.diplomacies[diplomacy.id] = DiplomacySnapshot(
                id = diplomacy.id,
                worldId = diplomacy.worldId,
                srcNationId = diplomacy.srcNationId,
                destNationId = diplomacy.destNationId,
                stateCode = diplomacy.stateCode,
                term = diplomacy.term,
                isDead = diplomacy.isDead,
                isShowing = diplomacy.isShowing,
                meta = diplomacy.meta.toMutableMap(),
                createdAt = diplomacy.createdAt,
            )
        }

        generalTurnRepository.findByWorldId(worldId)
            .groupBy { it.generalId }
            .forEach { (generalId, turns) ->
                state.generalTurnsByGeneralId[generalId] = turns
                    .sortedBy { it.turnIdx }
                    .map { turn ->
                        GeneralTurnSnapshot(
                            id = turn.id,
                            worldId = turn.worldId,
                            generalId = turn.generalId,
                            turnIdx = turn.turnIdx,
                            actionCode = turn.actionCode,
                            arg = turn.arg.toMutableMap(),
                            brief = turn.brief,
                            createdAt = turn.createdAt,
                        )
                    }
                    .toMutableList()
            }

        nationTurnRepository.findByWorldId(worldId)
            .groupBy { NationTurnKey(it.nationId, it.officerLevel) }
            .forEach { (key, turns) ->
                state.nationTurnsByNationAndLevel[key] = turns
                    .sortedBy { it.turnIdx }
                    .map { turn ->
                        NationTurnSnapshot(
                            id = turn.id,
                            worldId = turn.worldId,
                            nationId = turn.nationId,
                            officerLevel = turn.officerLevel,
                            turnIdx = turn.turnIdx,
                            actionCode = turn.actionCode,
                            arg = turn.arg.toMutableMap(),
                            brief = turn.brief,
                            createdAt = turn.createdAt,
                        )
                    }
                    .toMutableList()
            }

        return state
    }
}
