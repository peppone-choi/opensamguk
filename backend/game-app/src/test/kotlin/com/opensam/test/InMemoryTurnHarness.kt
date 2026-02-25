package com.opensam.test

import com.opensam.command.CommandExecutor
import com.opensam.command.CommandRegistry
import com.opensam.engine.*
import com.opensam.engine.ai.GeneralAI
import com.opensam.engine.ai.NationAI
import com.opensam.engine.modifier.ModifierService
import com.opensam.repository.TrafficSnapshotRepository
import com.opensam.service.WorldService
import com.opensam.entity.*
import com.opensam.repository.*
import com.opensam.service.InheritanceService
import com.opensam.service.MapService
import com.opensam.service.ScenarioService
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.OffsetDateTime
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong

class InMemoryTurnHarness {
    private val worlds = mutableMapOf<Short, WorldState>()
    private val generals = mutableMapOf<Long, General>()
    private val cities = mutableMapOf<Long, City>()
    private val nations = mutableMapOf<Long, Nation>()
    private val generalTurns = mutableMapOf<Long, MutableList<GeneralTurn>>()
    private val nationTurns = mutableMapOf<Pair<Long, Short>, MutableList<NationTurn>>()
    private val turnIdSeq = AtomicLong(1)

    val worldStateRepository: WorldStateRepository = mock(WorldStateRepository::class.java)
    val generalRepository: GeneralRepository = mock(GeneralRepository::class.java)
    val generalTurnRepository: GeneralTurnRepository = mock(GeneralTurnRepository::class.java)
    val nationTurnRepository: NationTurnRepository = mock(NationTurnRepository::class.java)
    val cityRepository: CityRepository = mock(CityRepository::class.java)
    val nationRepository: NationRepository = mock(NationRepository::class.java)
    val diplomacyRepository: DiplomacyRepository = mock(DiplomacyRepository::class.java)
    private val mapService: MapService = MapService().apply { init() }

    private val scenarioService: ScenarioService = mock(ScenarioService::class.java)
    private val economyService: EconomyService = mock(EconomyService::class.java)
    private val eventService: EventService = mock(EventService::class.java)
    private val diplomacyService: DiplomacyService = mock(DiplomacyService::class.java)
    private val generalMaintenanceService: GeneralMaintenanceService = mock(GeneralMaintenanceService::class.java)
    private val specialAssignmentService: SpecialAssignmentService = mock(SpecialAssignmentService::class.java)
    private val npcSpawnService: NpcSpawnService = mock(NpcSpawnService::class.java)
    val unificationService: UnificationService = mock(UnificationService::class.java)
    private val inheritanceService: InheritanceService = mock(InheritanceService::class.java)
    private val generalAI: GeneralAI = mock(GeneralAI::class.java)
    private val nationAI: NationAI = mock(NationAI::class.java)
    private val statChangeService: StatChangeService = mock(StatChangeService::class.java)
    private val modifierService: ModifierService = mock(ModifierService::class.java)

    val commandRegistry = CommandRegistry()
    val commandExecutor = CommandExecutor(
        commandRegistry,
        generalRepository,
        cityRepository,
        nationRepository,
        diplomacyRepository,
        diplomacyService,
        mapService,
        statChangeService,
        modifierService,
    )

    private val yearbookService: YearbookService = mock(YearbookService::class.java)
    private val auctionService: com.opensam.service.AuctionService = mock(com.opensam.service.AuctionService::class.java)
    private val tournamentService: com.opensam.service.TournamentService = mock(com.opensam.service.TournamentService::class.java)
    private val trafficSnapshotRepository: TrafficSnapshotRepository = mock(TrafficSnapshotRepository::class.java)
    private val worldService: WorldService = mock(WorldService::class.java)

    val turnService = TurnService(
        worldStateRepository,
        generalRepository,
        generalTurnRepository,
        nationTurnRepository,
        cityRepository,
        nationRepository,
        commandExecutor,
        commandRegistry,
        scenarioService,
        economyService,
        eventService,
        diplomacyService,
        generalMaintenanceService,
        specialAssignmentService,
        npcSpawnService,
        unificationService,
        inheritanceService,
        yearbookService,
        auctionService,
        tournamentService,
        trafficSnapshotRepository,
        generalAI,
        nationAI,
        modifierService,
        worldService,
    )

    init {
        wireRepositories()
    }

    fun putWorld(world: WorldState) {
        worlds[world.id] = world
    }

    fun putGeneral(general: General) {
        generals[general.id] = general
    }

    fun putCity(city: City) {
        cities[city.id] = city
    }

    fun putNation(nation: Nation) {
        nations[nation.id] = nation
    }

    fun queueGeneralTurn(generalId: Long, actionCode: String, arg: MutableMap<String, Any> = mutableMapOf(), turnIdx: Short = 0) {
        val general = generals[generalId] ?: error("General not found: $generalId")
        val turn = GeneralTurn(
            id = turnIdSeq.getAndIncrement(),
            worldId = general.worldId,
            generalId = generalId,
            turnIdx = turnIdx,
            actionCode = actionCode,
            arg = arg,
            createdAt = OffsetDateTime.now(),
        )
        generalTurns.getOrPut(generalId) { mutableListOf() }.add(turn)
        generalTurns[generalId]!!.sortBy { it.turnIdx }
    }

    fun queueNationTurn(
        nationId: Long,
        officerLevel: Short,
        actionCode: String,
        arg: MutableMap<String, Any> = mutableMapOf(),
        turnIdx: Short = 0,
    ) {
        val nation = nations[nationId] ?: error("Nation not found: $nationId")
        val turn = NationTurn(
            id = turnIdSeq.getAndIncrement(),
            worldId = nation.worldId,
            nationId = nationId,
            officerLevel = officerLevel,
            turnIdx = turnIdx,
            actionCode = actionCode,
            arg = arg,
            createdAt = OffsetDateTime.now(),
        )
        val key = nationId to officerLevel
        nationTurns.getOrPut(key) { mutableListOf() }.add(turn)
        nationTurns[key]!!.sortBy { it.turnIdx }
    }

    fun generalTurnsFor(generalId: Long): List<GeneralTurn> = generalTurns[generalId]?.toList() ?: emptyList()

    fun nationTurnsFor(nationId: Long, officerLevel: Short): List<NationTurn> =
        nationTurns[nationId to officerLevel]?.toList() ?: emptyList()

    private fun wireRepositories() {
        `when`(worldStateRepository.save(org.mockito.Mockito.any(WorldState::class.java))).thenAnswer {
            val world = it.arguments[0] as WorldState
            worlds[world.id] = world
            world
        }
        `when`(worldStateRepository.findById(org.mockito.Mockito.anyShort())).thenAnswer {
            Optional.ofNullable(worlds[it.arguments[0] as Short])
        }

        `when`(generalRepository.findByWorldId(org.mockito.Mockito.anyLong())).thenAnswer {
            val worldId = it.arguments[0] as Long
            generals.values.filter { g -> g.worldId == worldId }
        }
        `when`(generalRepository.findByNationId(org.mockito.Mockito.anyLong())).thenAnswer {
            val nationId = it.arguments[0] as Long
            generals.values.filter { g -> g.nationId == nationId }
        }
        `when`(generalRepository.findById(org.mockito.Mockito.anyLong())).thenAnswer {
            Optional.ofNullable(generals[it.arguments[0] as Long])
        }
        `when`(generalRepository.save(org.mockito.Mockito.any(General::class.java))).thenAnswer {
            val g = it.arguments[0] as General
            generals[g.id] = g
            g
        }
        `when`(generalRepository.saveAll(org.mockito.Mockito.anyList<General>())).thenAnswer {
            val list = it.arguments[0] as List<General>
            list.forEach { g -> generals[g.id] = g }
            list
        }

        `when`(generalTurnRepository.findByGeneralIdOrderByTurnIdx(org.mockito.Mockito.anyLong())).thenAnswer {
            val generalId = it.arguments[0] as Long
            generalTurns[generalId]?.sortedBy { t -> t.turnIdx } ?: emptyList<GeneralTurn>()
        }

        doAnswer { invocation ->
            val generalId = invocation.arguments[0] as Long
            generalTurns.remove(generalId)
            null
        }.`when`(generalTurnRepository).deleteByGeneralId(org.mockito.Mockito.anyLong())
        doAnswer { invocation ->
            val turn = invocation.arguments[0] as GeneralTurn
            generalTurns[turn.generalId]?.removeIf { t -> t.id == turn.id }
            null
        }.`when`(generalTurnRepository).delete(org.mockito.Mockito.any(GeneralTurn::class.java))
        doAnswer { invocation ->
            val turns = invocation.arguments[0] as List<GeneralTurn>
            turns.forEach { turn ->
                generalTurns[turn.generalId]?.removeIf { t -> t.id == turn.id }
            }
            null
        }.`when`(generalTurnRepository).deleteAll(org.mockito.Mockito.anyList<GeneralTurn>())

        `when`(nationTurnRepository.findByNationIdAndOfficerLevelOrderByTurnIdx(org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyShort())).thenAnswer {
            val nationId = it.arguments[0] as Long
            val officerLevel = it.arguments[1] as Short
            nationTurns[nationId to officerLevel]?.sortedBy { t -> t.turnIdx } ?: emptyList<NationTurn>()
        }
        doAnswer { invocation ->
            val turn = invocation.arguments[0] as NationTurn
            nationTurns[turn.nationId to turn.officerLevel]?.removeIf { t -> t.id == turn.id }
            null
        }.`when`(nationTurnRepository).delete(org.mockito.Mockito.any(NationTurn::class.java))
        doAnswer { invocation ->
            val nationId = invocation.arguments[0] as Long
            val officerLevel = invocation.arguments[1] as Short
            nationTurns.remove(nationId to officerLevel)
            null
        }.`when`(nationTurnRepository).deleteByNationIdAndOfficerLevel(org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyShort())

        `when`(cityRepository.findById(org.mockito.Mockito.anyLong())).thenAnswer {
            Optional.ofNullable(cities[it.arguments[0] as Long])
        }
        `when`(cityRepository.findByWorldId(org.mockito.Mockito.anyLong())).thenAnswer {
            val worldId = it.arguments[0] as Long
            cities.values.filter { c -> c.worldId == worldId }
        }

        `when`(nationRepository.findById(org.mockito.Mockito.anyLong())).thenAnswer {
            Optional.ofNullable(nations[it.arguments[0] as Long])
        }
        `when`(nationRepository.findByWorldId(org.mockito.Mockito.anyLong())).thenAnswer {
            val worldId = it.arguments[0] as Long
            nations.values.filter { n -> n.worldId == worldId }
        }
        `when`(nationRepository.saveAll(org.mockito.Mockito.anyList<Nation>())).thenAnswer {
            val list = it.arguments[0] as List<Nation>
            list.forEach { n -> nations[n.id] = n }
            list
        }

        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(org.mockito.Mockito.anyLong())).thenReturn(emptyList())
    }
}
