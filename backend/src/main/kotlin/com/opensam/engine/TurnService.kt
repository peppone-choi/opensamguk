package com.opensam.engine

import com.opensam.command.CommandEnv
import com.opensam.command.CommandExecutor
import com.opensam.command.CommandRegistry
import com.opensam.engine.ai.GeneralAI
import com.opensam.engine.ai.NationAI
import com.opensam.engine.trigger.TriggerCaller
import com.opensam.engine.trigger.TriggerEnv
import com.opensam.engine.trigger.buildPreTurnTriggers
import com.opensam.entity.WorldState
import com.opensam.repository.*
import com.opensam.service.InheritanceService
import com.opensam.service.ScenarioService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime

@Service
class TurnService(
    private val worldStateRepository: WorldStateRepository,
    private val generalRepository: GeneralRepository,
    private val generalTurnRepository: GeneralTurnRepository,
    private val nationTurnRepository: NationTurnRepository,
    private val cityRepository: CityRepository,
    private val nationRepository: NationRepository,
    private val commandExecutor: CommandExecutor,
    private val commandRegistry: CommandRegistry,
    private val scenarioService: ScenarioService,
    private val economyService: EconomyService,
    private val eventService: EventService,
    private val diplomacyService: DiplomacyService,
    private val generalMaintenanceService: GeneralMaintenanceService,
    private val specialAssignmentService: SpecialAssignmentService,
    private val npcSpawnService: NpcSpawnService,
    private val inheritanceService: InheritanceService,
    private val generalAI: GeneralAI,
    private val nationAI: NationAI,
) {
    private val logger = LoggerFactory.getLogger(TurnService::class.java)

    @Transactional
    fun processWorld(world: WorldState) {
        val now = OffsetDateTime.now()
        val tickDuration = Duration.ofSeconds(world.tickSeconds.toLong())
        var nextTurnAt = world.updatedAt.plus(tickDuration)

        while (!now.isBefore(nextTurnAt)) {
            executeGeneralCommands(world)

            try {
                eventService.dispatchEvents(world, "PRE_MONTH")
            } catch (e: Exception) {
                logger.warn("EventService.dispatchEvents(PRE_MONTH) failed: ${e.message}")
            }

            advanceMonth(world)

            try {
                economyService.processMonthly(world)
            } catch (e: Exception) {
                logger.warn("EconomyService.processMonthly failed: ${e.message}")
            }

            try {
                economyService.processDisasterOrBoom(world)
            } catch (e: Exception) {
                logger.warn("EconomyService.processDisasterOrBoom failed: ${e.message}")
            }

            try {
                economyService.randomizeCityTradeRate(world)
            } catch (e: Exception) {
                logger.warn("EconomyService.randomizeCityTradeRate failed: ${e.message}")
            }

            try {
                eventService.dispatchEvents(world, "MONTH")
            } catch (e: Exception) {
                logger.warn("EventService.dispatchEvents failed: ${e.message}")
            }

            try {
                diplomacyService.processDiplomacyTurn(world)
            } catch (e: Exception) {
                logger.warn("DiplomacyService.processDiplomacyTurn failed: ${e.message}")
            }

            try {
                val generals = generalRepository.findByWorldId(world.id.toLong())
                generalMaintenanceService.processGeneralMaintenance(world, generals)
                specialAssignmentService.checkAndAssignSpecials(world, generals)
                generalRepository.saveAll(generals)

                // Accrue inheritance points for player generals
                for (general in generals.filter { it.npcState.toInt() == 0 }) {
                    inheritanceService.accruePoints(general, "lived_month", 1)
                }
            } catch (e: Exception) {
                logger.warn("GeneralMaintenanceService failed: ${e.message}")
            }

            try {
                npcSpawnService.checkNpcSpawn(world)
            } catch (e: Exception) {
                logger.warn("NpcSpawnService.checkNpcSpawn failed: ${e.message}")
            }

            world.updatedAt = nextTurnAt
            nextTurnAt = nextTurnAt.plus(tickDuration)
        }

        worldStateRepository.save(world)
    }

    private fun executeGeneralCommands(world: WorldState) {
        val worldId = world.id.toLong()
        val generals = generalRepository.findByWorldId(worldId).sortedBy { it.turnTime }
        val env = buildCommandEnv(world)

        for (general in generals) {
            try {
                val city = cityRepository.findById(general.cityId).orElse(null)
                val nation = if (general.nationId != 0L) {
                    nationRepository.findById(general.nationId).orElse(null)
                } else null

                firePreTurnTriggers(world, general)

                if (general.blockState >= 2) {
                    if (general.killTurn != null) {
                        val kt = general.killTurn!! - 1
                        if (kt <= 0) {
                            general.npcState = 5
                            general.nationId = 0
                            general.killTurn = null
                        } else {
                            general.killTurn = kt.toShort()
                        }
                    }
                    general.turnTime = OffsetDateTime.now()
                    general.updatedAt = OffsetDateTime.now()
                    generalRepository.save(general)
                    continue
                }

                // Nation command for high-ranking officers
                if (general.officerLevel >= 5 && nation != null) {
                    val nationTurns = nationTurnRepository
                        .findByNationIdAndOfficerLevelOrderByTurnIdx(general.nationId, general.officerLevel)
                    var nationActionCode: String? = null
                    var nationArg: Map<String, Any>? = null
                    var consumedNationTurn: com.opensam.entity.NationTurn? = null

                    if (nationTurns.isNotEmpty()) {
                        val nt = nationTurns.first()
                        nationActionCode = nt.actionCode
                        nationArg = nt.arg
                        consumedNationTurn = nt
                    } else if (general.npcState >= 2 && general.officerLevel >= 12) {
                        val aiAction = nationAI.decideNationAction(
                            nation,
                            world,
                            DeterministicRng.create(
                                "${world.id}",
                                "nation_ai",
                                general.id,
                                world.currentYear,
                                world.currentMonth,
                            )
                        )
                        if (aiAction != "Nation휴식") {
                            nationActionCode = aiAction
                        }
                    }

                    if (nationActionCode != null && commandRegistry.hasNationCommand(nationActionCode)) {
                        try {
                            runBlocking {
                                commandExecutor.executeNationCommand(
                                    nationActionCode,
                                    general,
                                    env,
                                    nationArg,
                                    city,
                                    nation,
                                    DeterministicRng.create(
                                        "${world.id}",
                                        "nation",
                                        general.id,
                                        world.currentYear,
                                        world.currentMonth,
                                        nationActionCode,
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            logger.warn("Nation command $nationActionCode failed for general ${general.id}: ${e.message}")
                        }
                    }

                    if (consumedNationTurn != null) {
                        nationTurnRepository.delete(consumedNationTurn)
                    }
                }

                // General command
                val actionCode: String
                val arg: Map<String, Any>?
                val executedTurn: com.opensam.entity.GeneralTurn?

                if (general.npcState >= 2) {
                    // NPC generals: let AI decide action
                    actionCode = generalAI.decideAndExecute(general, world)
                    arg = null
                    executedTurn = null
                    // Consume any queued turns for NPC generals
                    val npcTurns = generalTurnRepository.findByGeneralIdOrderByTurnIdx(general.id)
                    if (npcTurns.isNotEmpty()) {
                        generalTurnRepository.deleteAll(npcTurns)
                    }
                } else {
                    val generalTurns = generalTurnRepository.findByGeneralIdOrderByTurnIdx(general.id)
                    if (generalTurns.isNotEmpty()) {
                        val gt = generalTurns.first()
                        actionCode = gt.actionCode
                        arg = gt.arg
                        executedTurn = gt
                    } else {
                        actionCode = "휴식"
                        arg = null
                        executedTurn = null
                    }
                }

                val rng = DeterministicRng.create(
                    "${world.id}", "general", general.id, world.currentYear, world.currentMonth, actionCode
                )
                if (commandRegistry.hasNationCommand(actionCode) && general.officerLevel >= 5 && nation != null) {
                    runBlocking {
                        commandExecutor.executeNationCommand(actionCode, general, env, arg, city, nation, rng)
                    }
                } else {
                    runBlocking {
                        commandExecutor.executeGeneralCommand(actionCode, general, env, arg, city, nation, rng)
                    }
                }

                if (executedTurn != null) {
                    generalTurnRepository.delete(executedTurn)
                }

                // KillTurn handling
                if (general.killTurn != null) {
                    if (actionCode != "휴식" && general.npcState.toInt() == 0) {
                        general.killTurn = null
                    } else {
                        val kt = general.killTurn!! - 1
                        if (kt <= 0) {
                            general.npcState = 5
                            general.nationId = 0
                            general.killTurn = null
                        } else {
                            general.killTurn = kt.toShort()
                        }
                    }
                }

                general.turnTime = OffsetDateTime.now()
                general.updatedAt = OffsetDateTime.now()
                generalRepository.save(general)
            } catch (e: Exception) {
                logger.error("Error processing general ${general.id}: ${e.message}", e)
            }
        }
    }

    private fun buildCommandEnv(world: WorldState): CommandEnv {
        val startYear = try {
            scenarioService.getScenario(world.scenarioCode).startYear
        } catch (_: Exception) {
            world.currentYear.toInt()
        }

        return CommandEnv(
            year = world.currentYear.toInt(),
            month = world.currentMonth.toInt(),
            startYear = startYear,
            worldId = world.id.toLong(),
            realtimeMode = world.realtimeMode,
        )
    }

    private fun advanceMonth(world: WorldState) {
        val nextMonth = world.currentMonth + 1
        if (nextMonth > 12) {
            world.currentMonth = 1
            world.currentYear = (world.currentYear + 1).toShort()
        } else {
            world.currentMonth = nextMonth.toShort()
        }
    }

    private fun firePreTurnTriggers(world: WorldState, general: com.opensam.entity.General) {
        val triggers = buildPreTurnTriggers(general)
        if (triggers.isEmpty()) return

        val caller = TriggerCaller()
        caller.addAll(triggers)
        caller.fire(
            TriggerEnv(
                worldId = world.id.toLong(),
                year = world.currentYear.toInt(),
                month = world.currentMonth.toInt(),
                generalId = general.id,
            )
        )
    }
}
