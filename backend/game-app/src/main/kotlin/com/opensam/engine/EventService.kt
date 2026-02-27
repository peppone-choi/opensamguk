package com.opensam.engine

import com.opensam.entity.Message
import com.opensam.entity.WorldState
import com.opensam.repository.EventRepository
import com.opensam.repository.MessageRepository
import com.opensam.repository.NationRepository
import com.opensam.service.ScenarioService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class EventService(
    private val eventRepository: EventRepository,
    private val nationRepository: NationRepository,
    private val messageRepository: MessageRepository,
    private val economyService: EconomyService,
    private val npcSpawnService: NpcSpawnService,
    private val scenarioService: ScenarioService,
    private val eventActionService: EventActionService,
) {
    private val log = LoggerFactory.getLogger(EventService::class.java)

    @Transactional
    fun dispatchEvents(world: WorldState, targetCode: String) {
        val events = eventRepository.findByWorldIdAndTargetCodeOrderByPriorityDescIdAsc(
            world.id.toLong(), targetCode
        )

        for (event in events) {
            if (evaluateCondition(event.condition, world)) {
                log.info("Event #{} triggered (target={}, priority={})", event.id, targetCode, event.priority)
                executeAction(event.action, world, event.id)
            }
        }
    }

    private fun evaluateCondition(condition: Map<String, Any>, world: WorldState): Boolean {
        return when (val type = condition["type"] as? String) {
            "always_true" -> true
            "always_false" -> false

            "date" -> {
                val year = (condition["year"] as? Number)?.toShort() ?: return false
                val month = (condition["month"] as? Number)?.toShort() ?: return false
                world.currentYear == year && world.currentMonth == month
            }

            "date_after" -> {
                val year = (condition["year"] as? Number)?.toShort() ?: return false
                val month = (condition["month"] as? Number)?.toShort() ?: return false
                world.currentYear > year || (world.currentYear == year && world.currentMonth >= month)
            }

            // 시나리오 시작년도 기준 상대 날짜 조건
            "date_relative" -> {
                val yearOffset = (condition["yearOffset"] as? Number)?.toInt() ?: return false
                val month = (condition["month"] as? Number)?.toShort() ?: return false
                val startYear = try {
                    scenarioService.getScenario(world.scenarioCode).startYear
                } catch (_: Exception) {
                    return false
                }
                val targetYear = (startYear + yearOffset).toShort()
                world.currentYear == targetYear && world.currentMonth == month
            }

            // 반복 조건: startYear/startMonth부터 매 N개월마다 트리거
            "interval" -> {
                val months = (condition["months"] as? Number)?.toInt() ?: return false
                val startYear = (condition["startYear"] as? Number)?.toInt() ?: return false
                val startMonth = (condition["startMonth"] as? Number)?.toInt() ?: 1
                if (months <= 0) return false
                val startTotal = startYear * 12 + (startMonth - 1)
                val currentTotal = world.currentYear.toInt() * 12 + (world.currentMonth.toInt() - 1)
                val elapsed = currentTotal - startTotal
                elapsed >= 0 && elapsed % months == 0
            }

            "remain_nation" -> {
                val count = (condition["count"] as? Number)?.toInt() ?: return false
                val nationCount = nationRepository.findByWorldId(world.id.toLong()).size
                nationCount <= count
            }

            "and" -> {
                @Suppress("UNCHECKED_CAST")
                val conditions = condition["conditions"] as? List<Map<String, Any>> ?: return false
                conditions.all { evaluateCondition(it, world) }
            }

            "or" -> {
                @Suppress("UNCHECKED_CAST")
                val conditions = condition["conditions"] as? List<Map<String, Any>> ?: return false
                conditions.any { evaluateCondition(it, world) }
            }

            "not" -> {
                @Suppress("UNCHECKED_CAST")
                val sub = condition["condition"] as? Map<String, Any> ?: return false
                !evaluateCondition(sub, world)
            }

            else -> {
                log.warn("Unknown condition type: {}", type)
                false
            }
        }
    }

    private fun executeAction(action: Map<String, Any>, world: WorldState, currentEventId: Long = 0) {
        when (val type = action["type"] as? String) {
            "log" -> {
                val message = action["message"] as? String ?: ""
                log.info("[World {}] History: {}", world.id, message)
                messageRepository.save(
                    Message(
                        worldId = world.id.toLong(),
                        mailboxCode = "world_history",
                        messageType = "history",
                        payload = mutableMapOf(
                            "message" to message,
                            "year" to world.currentYear.toInt(),
                            "month" to world.currentMonth.toInt(),
                        ),
                    )
                )
            }

            "delete_event" -> {
                val eventId = (action["eventId"] as? Number)?.toLong() ?: return
                eventRepository.deleteById(eventId)
                log.info("[World {}] Deleted event #{}", world.id, eventId)
            }

            "delete_self" -> {
                if (currentEventId > 0) {
                    eventRepository.deleteById(currentEventId)
                    log.info("[World {}] Event #{} deleted itself", world.id, currentEventId)
                }
            }

            "notice" -> {
                val message = action["message"] as? String ?: ""
                messageRepository.save(
                    Message(
                        worldId = world.id.toLong(),
                        mailboxCode = "notice",
                        messageType = "notice",
                        payload = mutableMapOf(
                            "message" to message,
                            "year" to world.currentYear.toInt(),
                            "month" to world.currentMonth.toInt(),
                        ),
                    )
                )
                log.info("[World {}] Notice: {}", world.id, message)
            }

            // Economy-related actions delegating to EconomyService
            "process_income" -> {
                economyService.processIncomeEvent(world)
                log.info("[World {}] Event action: process_income", world.id)
            }

            "process_semi_annual" -> {
                economyService.processSemiAnnualEvent(world)
                log.info("[World {}] Event action: process_semi_annual", world.id)
            }

            "update_city_supply" -> {
                economyService.updateCitySupplyState(world)
                log.info("[World {}] Event action: update_city_supply", world.id)
            }

            "update_nation_level" -> {
                economyService.updateNationLevelEvent(world)
                log.info("[World {}] Event action: update_nation_level", world.id)
            }

            "randomize_trade_rate" -> {
                economyService.randomizeCityTradeRate(world)
                log.info("[World {}] Event action: randomize_trade_rate", world.id)
            }

            "raise_invader" -> {
                npcSpawnService.raiseInvader(world)
                log.info("[World {}] Event action: raise_invader", world.id)
            }

            "raise_npc_nation" -> {
                npcSpawnService.checkNpcSpawn(world)
                log.info("[World {}] Event action: raise_npc_nation", world.id)
            }

            "provide_npc_troop_leader" -> {
                npcSpawnService.provideNpcTroopLeaders(world)
                log.info("[World {}] Event action: provide_npc_troop_leader", world.id)
            }

            // Compound action: execute multiple sub-actions sequentially
            "compound" -> {
                @Suppress("UNCHECKED_CAST")
                val actions = action["actions"] as? List<Map<String, Any>> ?: return
                for (subAction in actions) {
                    executeAction(subAction, world, currentEventId)
                }
            }

            // ── 19 ported event actions from PHP legacy ──

            "add_global_betray" -> {
                val cnt = (action["cnt"] as? Number)?.toInt() ?: 1
                val ifMax = (action["ifMax"] as? Number)?.toInt() ?: 0
                eventActionService.addGlobalBetray(world, cnt, ifMax)
            }

            "assign_general_speciality" -> {
                eventActionService.assignGeneralSpeciality(world)
            }

            "auto_delete_invader" -> {
                val nationId = (action["nationId"] as? Number)?.toLong() ?: return
                eventActionService.autoDeleteInvader(world, nationId, currentEventId)
            }

            "block_scout_action" -> {
                val blockChangeScout = action["blockChangeScout"] as? Boolean
                eventActionService.blockScoutAction(world, blockChangeScout)
            }

            "unblock_scout_action" -> {
                val blockChangeScout = action["blockChangeScout"] as? Boolean
                eventActionService.unblockScoutAction(world, blockChangeScout)
            }

            "change_city" -> {
                val target = action["target"]
                @Suppress("UNCHECKED_CAST")
                val changes = action["changes"] as? Map<String, Any> ?: return
                eventActionService.changeCity(world, target, changes)
            }

            "create_admin_npc" -> {
                eventActionService.createAdminNPC(world)
            }

            "create_many_npc" -> {
                val npcCount = (action["npcCount"] as? Number)?.toInt() ?: 10
                val fillCnt = (action["fillCnt"] as? Number)?.toInt() ?: 0
                eventActionService.createManyNPC(world, npcCount, fillCnt)
            }

            "finish_nation_betting" -> {
                val bettingId = (action["bettingId"] as? Number)?.toLong() ?: return
                eventActionService.finishNationBetting(world, bettingId)
            }

            "open_nation_betting" -> {
                val nationCnt = (action["nationCnt"] as? Number)?.toInt() ?: 1
                val bonusPoint = (action["bonusPoint"] as? Number)?.toInt() ?: 0
                eventActionService.openNationBetting(world, nationCnt, bonusPoint)
            }

            "invader_ending" -> {
                eventActionService.invaderEnding(world, currentEventId)
            }

            "lost_unique_item" -> {
                val lostProb = (action["lostProb"] as? Number)?.toDouble() ?: 0.1
                eventActionService.lostUniqueItem(world, lostProb)
            }

            "merge_inherit_point_rank" -> {
                eventActionService.mergeInheritPointRank(world)
            }

            "new_year" -> {
                eventActionService.newYear(world)
            }

            "process_war_income" -> {
                eventActionService.processWarIncome(world)
            }

            "raise_disaster" -> {
                economyService.processDisasterOrBoom(world)
            }

            "reg_npc" -> {
                @Suppress("UNCHECKED_CAST")
                val params = action.filterKeys { it != "type" }
                eventActionService.regNPC(world, params)
            }

            "reg_neutral_npc" -> {
                @Suppress("UNCHECKED_CAST")
                val params = action.filterKeys { it != "type" }
                eventActionService.regNeutralNPC(world, params)
            }

            "reset_officer_lock" -> {
                eventActionService.resetOfficerLock(world)
            }

            else -> {
                log.warn("[World {}] Unknown action type: {}", world.id, type)
            }
        }
    }
}
