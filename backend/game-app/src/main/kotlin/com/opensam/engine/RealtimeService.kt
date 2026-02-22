package com.opensam.engine

import com.opensam.command.CommandEnv
import com.opensam.command.CommandExecutor
import com.opensam.command.CommandResult
import com.opensam.command.CommandRegistry
import com.opensam.command.constraint.ConstraintResult
import com.opensam.engine.modifier.ModifierService
import com.opensam.engine.trigger.TriggerCaller
import com.opensam.engine.trigger.TriggerEnv
import com.opensam.engine.trigger.buildPreTurnTriggers
import com.opensam.entity.GeneralTurn
import com.opensam.entity.Nation
import com.opensam.entity.WorldState
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.GeneralTurnRepository
import com.opensam.repository.NationRepository
import com.opensam.repository.WorldStateRepository
import com.opensam.service.GameEventService
import com.opensam.service.ScenarioService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class RealtimeService(
    private val generalRepository: GeneralRepository,
    private val generalTurnRepository: GeneralTurnRepository,
    private val cityRepository: CityRepository,
    private val nationRepository: NationRepository,
    private val worldStateRepository: WorldStateRepository,
    private val commandExecutor: CommandExecutor,
    private val commandRegistry: CommandRegistry,
    private val gameEventService: GameEventService,
    private val scenarioService: ScenarioService,
    private val modifierService: ModifierService,
) {
    private val logger = LoggerFactory.getLogger(RealtimeService::class.java)

    @Transactional
    fun submitCommand(generalId: Long, actionCode: String, arg: Map<String, Any>?): CommandResult {
        val general = generalRepository.findById(generalId).orElseThrow {
            IllegalArgumentException("General not found: $generalId")
        }

        val world = worldStateRepository.findById(general.worldId.toShort()).orElseThrow {
            IllegalArgumentException("World not found: ${general.worldId}")
        }
        if (!world.realtimeMode) {
            return CommandResult(success = false, logs = listOf("This world is not in realtime mode."))
        }

        if (general.commandEndTime != null && general.commandEndTime!!.isAfter(OffsetDateTime.now())) {
            return CommandResult(success = false, logs = listOf("Command already in progress."))
        }

        return scheduleCommand(general, world, actionCode, arg, isNationCommand = false)
    }

    @Transactional
    fun submitNationCommand(generalId: Long, actionCode: String, arg: Map<String, Any>?): CommandResult {
        val general = generalRepository.findById(generalId).orElseThrow {
            IllegalArgumentException("General not found: $generalId")
        }

        if (general.officerLevel < 5) {
            return CommandResult(success = false, logs = listOf("국가 명령 권한이 없습니다."))
        }

        val world = worldStateRepository.findById(general.worldId.toShort()).orElseThrow {
            IllegalArgumentException("World not found: ${general.worldId}")
        }
        if (!world.realtimeMode) {
            return CommandResult(success = false, logs = listOf("This world is not in realtime mode."))
        }

        if (general.commandEndTime != null && general.commandEndTime!!.isAfter(OffsetDateTime.now())) {
            return CommandResult(success = false, logs = listOf("Command already in progress."))
        }

        return scheduleCommand(general, world, actionCode, arg, isNationCommand = true)
    }

    @Transactional
    fun processCompletedCommands(world: WorldState) {
        val now = OffsetDateTime.now()
        val generals = generalRepository.findByWorldIdAndCommandEndTimeBefore(world.id.toLong(), now)

        for (general in generals) {
            try {
                val turns = generalTurnRepository.findByGeneralIdOrderByTurnIdx(general.id)
                if (turns.isEmpty()) {
                    general.commandEndTime = null
                    generalRepository.save(general)
                    continue
                }

                val gt = turns.first()
                val env = buildCommandEnv(world)
                val city = cityRepository.findById(general.cityId).orElse(null)
                val nation = if (general.nationId != 0L) {
                    nationRepository.findById(general.nationId).orElse(null)
                } else null

                val rng = DeterministicRng.create(
                    "${world.id}", "realtime_complete", general.id, world.currentYear, world.currentMonth, gt.actionCode
                )

                firePreTurnTriggers(world, general, nation)

                val result = runBlocking {
                    if (commandRegistry.hasNationCommand(gt.actionCode)) {
                        commandExecutor.executeNationCommand(gt.actionCode, general, env, gt.arg, city, nation, rng)
                    } else {
                        commandExecutor.executeGeneralCommand(gt.actionCode, general, env, gt.arg, city, nation, rng)
                    }
                }

                generalTurnRepository.delete(gt)
                general.commandEndTime = null
                general.updatedAt = OffsetDateTime.now()
                generalRepository.save(general)

                gameEventService.sendToGeneral(general.id, mapOf(
                    "type" to "command_completed",
                    "actionCode" to gt.actionCode,
                    "success" to result.success,
                    "logs" to result.logs
                ))
            } catch (e: Exception) {
                logger.error("Error processing completed command for general ${general.id}: ${e.message}", e)
            }
        }
    }

    @Transactional
    fun regenerateCommandPoints(world: WorldState) {
        val generals = generalRepository.findByWorldId(world.id.toLong())
        for (general in generals) {
            val newCp = (general.commandPoints + world.commandPointRegenRate).coerceAtMost(100)
            if (newCp != general.commandPoints) {
                general.commandPoints = newCp
                generalRepository.save(general)
            }
        }
    }

    fun getRealtimeStatus(generalId: Long): Map<String, Any?>? {
        val general = generalRepository.findById(generalId).orElse(null) ?: return null
        val now = OffsetDateTime.now()
        val remainingSeconds = if (general.commandEndTime != null && general.commandEndTime!!.isAfter(now)) {
            java.time.Duration.between(now, general.commandEndTime).seconds
        } else {
            0L
        }
        return mapOf(
            "generalId" to general.id,
            "commandPoints" to general.commandPoints,
            "commandEndTime" to general.commandEndTime,
            "remainingSeconds" to remainingSeconds,
        )
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

    private fun scheduleCommand(
        general: com.opensam.entity.General,
        world: WorldState,
        actionCode: String,
        arg: Map<String, Any>?,
        isNationCommand: Boolean,
    ): CommandResult {
        val env = buildCommandEnv(world)
        val city = cityRepository.findById(general.cityId).orElse(null)
        val nation = if (general.nationId != 0L) {
            nationRepository.findById(general.nationId).orElse(null)
        } else null

        val command = if (isNationCommand) {
            commandRegistry.createNationCommand(actionCode, general, env, arg)
                ?: return CommandResult(success = false, logs = listOf("알 수 없는 국가 명령: $actionCode"))
        } else {
            commandRegistry.createGeneralCommand(actionCode, general, env, arg)
        }

        command.city = city
        command.nation = nation
        commandExecutor.hydrateCommandForConstraintCheck(command, general, env, arg)

        val conditionResult = command.checkFullCondition()
        if (conditionResult is ConstraintResult.Fail) {
            if (!isNationCommand) {
                val altCode = command.getAlternativeCommand()
                if (altCode != null && altCode != actionCode) {
                    return scheduleCommand(general, world, altCode, arg, isNationCommand = false)
                }
            }
            return CommandResult(success = false, logs = listOf(conditionResult.reason))
        }

        val commandPointCost = command.getCommandPointCost().coerceAtLeast(1)
        if (general.commandPoints < commandPointCost) {
            return CommandResult(
                success = false,
                logs = listOf("커맨드 포인트가 부족합니다. (필요: $commandPointCost, 보유: ${general.commandPoints})")
            )
        }

        val duration = command.getDuration().coerceAtLeast(1)

        general.commandPoints -= commandPointCost
        general.commandEndTime = OffsetDateTime.now().plusSeconds(duration.toLong())
        general.updatedAt = OffsetDateTime.now()
        generalRepository.save(general)

        generalTurnRepository.deleteByGeneralId(general.id)
        generalTurnRepository.save(
            GeneralTurn(
                worldId = general.worldId,
                generalId = general.id,
                turnIdx = 0,
                actionCode = actionCode,
                arg = arg?.toMutableMap() ?: mutableMapOf(),
                brief = command.actionName,
            )
        )

        gameEventService.sendToGeneral(
            general.id,
            mapOf(
                "type" to "command_scheduled",
                "actionCode" to actionCode,
                "name" to command.actionName,
                "commandPointCost" to commandPointCost,
                "durationSeconds" to duration,
                "commandEndTime" to general.commandEndTime,
                "remainingCommandPoints" to general.commandPoints,
            )
        )

        return CommandResult(
            success = true,
            logs = listOf("${command.actionName} 명령이 접수되었습니다. ${duration}초 후 실행됩니다."),
        )
    }

    private fun firePreTurnTriggers(world: WorldState, general: com.opensam.entity.General, nation: Nation?) {
        val modifiers = modifierService.getModifiers(general, nation)
        val triggers = buildPreTurnTriggers(general, modifiers)
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
