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
import com.opensam.service.AuctionService
import com.opensam.service.InheritanceService
import com.opensam.service.ScenarioService
import com.opensam.service.TournamentService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime

/**
 * 턴 서비스: 월드의 전체 턴 파이프라인을 실행한다.
 * 1. 장수 커맨드 실행 (AI 포함)
 * 2. 보급 상태 갱신
 * 3. 이벤트 (PRE_MONTH, MONTH)
 * 4. 월 진행
 * 5. 경제 파이프라인 (수입, 반기, 재해, 교역)
 * 6. 외교 턴 처리
 * 7. 장수 유지보수 (나이, 경험, 헌신, 부상, 은퇴)
 * 8. NPC 스폰, 통일 체크
 */
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
    private val unificationService: UnificationService,
    private val inheritanceService: InheritanceService,
    private val yearbookService: YearbookService,
    private val auctionService: AuctionService,
    private val tournamentService: TournamentService,
    private val generalAI: GeneralAI,
    private val nationAI: NationAI,
) {
    private val logger = LoggerFactory.getLogger(TurnService::class.java)

    @Transactional
    fun processWorld(world: WorldState) {
        val now = OffsetDateTime.now()
        val tickDuration = Duration.ofSeconds(world.tickSeconds.toLong())
        var nextTurnAt = world.updatedAt.plus(tickDuration)
        val worldId = world.id.toLong()

        while (!now.isBefore(nextTurnAt)) {
            // 진행 전 이전 월 기록 (연감 스냅샷용)
            val previousYear = world.currentYear.toInt()
            val previousMonth = world.currentMonth.toInt()

            executeGeneralCommands(world)

            try {
                updateTraffic(world)
            } catch (e: Exception) {
                logger.warn("updateTraffic failed: ${e.message}")
            }

            try {
                eventService.dispatchEvents(world, "PRE_MONTH")
            } catch (e: Exception) {
                logger.warn("EventService.dispatchEvents(PRE_MONTH) failed: ${e.message}")
            }

            advanceMonth(world)

            // 연감 스냅샷: 매월 변경 시 이전 월의 맵/국가 상태를 기록
            // core2026 yearbookHandler.onMonthChanged 패러티
            try {
                yearbookService.saveMonthlySnapshot(worldId, previousYear, previousMonth)
            } catch (e: Exception) {
                logger.warn("YearbookService.saveMonthlySnapshot failed: ${e.message}")
            }

            // 1월: 연초 통계 (legacy checkStatistic 패러티)
            if (world.currentMonth.toInt() == 1) {
                try {
                    economyService.processYearlyStatistics(world)
                } catch (e: Exception) {
                    logger.warn("EconomyService.processYearlyStatistics failed: ${e.message}")
                }
            }

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
                resetStrategicCommandLimits(world)
            } catch (e: Exception) {
                logger.warn("resetStrategicCommandLimits failed: ${e.message}")
            }

            try {
                val generals = generalRepository.findByWorldId(worldId)
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

            try {
                unificationService.checkAndSettleUnification(world)
            } catch (e: Exception) {
                logger.warn("UnificationService.checkAndSettleUnification failed: ${e.message}")
            }

            world.updatedAt = nextTurnAt
            nextTurnAt = nextTurnAt.plus(tickDuration)
        }

        // 토너먼트 처리: 자동 진행 라운드 (legacy processTournament 패러티)
        try {
            tournamentService.processTournamentTurn(worldId)
        } catch (e: Exception) {
            logger.warn("TournamentService.processTournamentTurn failed: ${e.message}")
        }

        // 경매 처리: 만료된 경매 정리 (legacy processAuction 패러티)
        try {
            auctionService.processExpiredAuctions()
        } catch (e: Exception) {
            logger.warn("AuctionService.processExpiredAuctions failed: ${e.message}")
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
                var hasReservedTurn = false

                // autorun_limit: 플레이어 장수가 일정 기간 미접속 시 AI가 대신 행동
                // legacy TurnExecutionHelper.php lines 289-296
                val useAutorun = general.npcState < 2 && run {
                    val currentYearMonth = world.currentYear.toInt() * 100 + world.currentMonth.toInt()
                    val autorunLimit = (general.meta["autorun_limit"] as? Number)?.toInt()
                        ?: ((world.currentYear.toInt() - 2) * 100 + world.currentMonth.toInt())
                    currentYearMonth < autorunLimit
                }

                if (general.npcState >= 2 || useAutorun) {
                    // NPC generals 또는 autorun 대상: AI가 행동 결정
                    actionCode = generalAI.decideAndExecute(general, world)
                    arg = null
                    executedTurn = null
                    // Consume any queued turns
                    val queuedTurns = generalTurnRepository.findByGeneralIdOrderByTurnIdx(general.id)
                    if (queuedTurns.isNotEmpty()) {
                        generalTurnRepository.deleteAll(queuedTurns)
                    }
                } else {
                    val generalTurns = generalTurnRepository.findByGeneralIdOrderByTurnIdx(general.id)
                    if (generalTurns.isNotEmpty()) {
                        val gt = generalTurns.first()
                        actionCode = gt.actionCode
                        arg = gt.arg
                        executedTurn = gt
                        if (actionCode != "휴식") hasReservedTurn = true
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

                // Track active actions for inheritance (core2026 parity)
                if (general.npcState.toInt() == 0 && actionCode != "휴식") {
                    inheritanceService.accruePoints(general, "active_action", 1)
                }

                // autorun_limit 갱신: 플레이어 장수가 예약된 턴을 실행했을 때
                // legacy TurnExecutionHelper.php lines 356-361
                val autorunUser = world.config["autorun_user"] as? Map<*, *>
                val limitMinutes = (autorunUser?.get("limit_minutes") as? Number)?.toInt()
                if (limitMinutes != null && limitMinutes > 0 && general.npcState < 2 && hasReservedTurn) {
                    val turnterm = world.tickSeconds / 60 // tick초 → 분 단위
                    val pushForward = if (turnterm > 0) limitMinutes / turnterm else 0
                    val currentYearMonth = world.currentYear.toInt() * 100 + world.currentMonth.toInt()
                    general.meta["autorun_limit"] = currentYearMonth + pushForward
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

    /**
     * Recalculate city supply state (traffic/supply routes) per turn.
     * Delegates to EconomyService which already has BFS-based supply logic.
     */
    private fun updateTraffic(world: WorldState) {
        economyService.updateCitySupplyState(world)
    }

    /**
     * Decrement strategic command limits for all nations each turn.
     * Per legacy: strategicCmdLimit decreases by 1 each turn until 0.
     */
    private fun resetStrategicCommandLimits(world: WorldState) {
        val nations = nationRepository.findByWorldId(world.id.toLong())
        for (nation in nations) {
            if (nation.strategicCmdLimit > 0) {
                nation.strategicCmdLimit = (nation.strategicCmdLimit - 1).toShort()
            }
        }
        nationRepository.saveAll(nations)
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
