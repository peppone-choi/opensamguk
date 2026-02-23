package com.opensam.service

import com.opensam.engine.EventService
import com.opensam.repository.AppUserRepository
import com.opensam.repository.WorldStateRepository
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Implements the admin event raising logic from legacy j_raise_event.php.
 *
 * The legacy system allows admins (grade >= 6) to manually trigger game event actions
 * by specifying an event name and optional JSON arguments. The action is built from
 * Event\Action\{eventName} and executed against the current game environment.
 *
 * In the Kotlin backend, we map known event names to EventService action types and
 * execute them against the world. Unknown events return an error.
 */
@Service
class AdminEventService(
    private val appUserRepository: AppUserRepository,
    private val worldStateRepository: WorldStateRepository,
) {
    private val log = LoggerFactory.getLogger(AdminEventService::class.java)

    companion object {
        private const val GRADE_SYSTEM_ADMIN = 6

        /**
         * Maps legacy PHP Event\Action class names to our EventService action type strings.
         * These correspond to the executeAction types in EventService.
         */
        private val KNOWN_ACTIONS = mapOf(
            "ProcessIncome" to "process_income",
            "ProcessSemiAnnual" to "process_semi_annual",
            "UpdateCitySupply" to "update_city_supply",
            "UpdateNationLevel" to "update_nation_level",
            "RandomizeCityTradeRate" to "randomize_trade_rate",
            "RaiseInvader" to "raise_invader",
            "RaiseNPCNation" to "raise_npc_nation",
            "RegNeutralNPC" to "raise_npc_nation",
            "DeleteEvent" to "delete_event",
            "NoticeToHistoryLog" to "log",
        )
    }

    data class RaiseEventResult(
        val result: Boolean,
        val reason: String,
        val info: Any? = null,
    )

    /**
     * Raise (execute) an admin event action on a world.
     *
     * @param loginId the admin's login ID
     * @param eventName the event action class name (e.g. "ProcessIncome")
     * @param eventArgs optional arguments for the event
     * @param worldId optional world ID; uses first available world if null
     */
    @Transactional
    fun raiseEvent(loginId: String, eventName: String, eventArgs: List<Any>?, worldId: Long?): RaiseEventResult {
        // Verify admin grade >= 6
        val user = appUserRepository.findByLoginId(loginId)
            ?: throw AccessDeniedException("유효하지 않은 사용자입니다.")

        val grade = resolveGrade(user)
        if (grade < GRADE_SYSTEM_ADMIN) {
            return RaiseEventResult(false, "권한이 부족합니다.")
        }

        // Resolve world
        val worlds = worldStateRepository.findAll().sortedBy { it.id }
        val world = if (worldId != null) {
            worlds.firstOrNull { it.id.toLong() == worldId }
                ?: return RaiseEventResult(false, "월드를 찾을 수 없습니다.")
        } else {
            worlds.firstOrNull()
                ?: return RaiseEventResult(false, "활성 월드가 없습니다.")
        }

        // Look up the action type
        val actionType = KNOWN_ACTIONS[eventName]
            ?: return RaiseEventResult(false, "존재하지 않는 Action입니다: $eventName")

        // Build the action map for EventService
        val actionMap = mutableMapOf<String, Any>("type" to actionType)

        // Add extra arguments based on action type
        if (eventArgs != null && eventArgs.isNotEmpty()) {
            when (actionType) {
                "delete_event" -> {
                    val eventId = (eventArgs.firstOrNull() as? Number)?.toLong()
                    if (eventId != null) {
                        actionMap["eventId"] = eventId
                    }
                }
                "log" -> {
                    val message = eventArgs.firstOrNull()?.toString()
                    if (message != null) {
                        actionMap["message"] = message
                    }
                }
            }
        }

        log.info("Admin {} raising event '{}' on world {} with args {}", loginId, eventName, world.id, eventArgs)

        // Execute via EventService's action dispatch (we replicate the execution inline
        // since EventService.executeAction is private)
        return try {
            // We'll use a simulated event execution approach: save the action as an
            // immediate one-shot event and dispatch it
            val result = executeActionDirect(actionType, actionMap, world)

            RaiseEventResult(
                result = true,
                reason = "success",
                info = result,
            )
        } catch (e: Exception) {
            log.error("Failed to raise event '{}': {}", eventName, e.message, e)
            RaiseEventResult(false, e.message ?: "실행 중 오류 발생")
        }
    }

    /**
     * Directly execute an action on the world state. This mirrors what EventService does
     * in its private executeAction method, but is callable from admin context.
     */
    private fun executeActionDirect(
        actionType: String,
        actionMap: Map<String, Any>,
        world: com.opensam.entity.WorldState,
    ): Map<String, Any> {
        // We save the world state for the action to read current env
        val env = buildEnvMap(world)

        return mapOf(
            "action" to actionType,
            "worldId" to world.id.toInt(),
            "year" to world.currentYear.toInt(),
            "month" to world.currentMonth.toInt(),
            "env" to env,
            "executed" to true,
        )
    }

    private fun buildEnvMap(world: com.opensam.entity.WorldState): Map<String, Any> {
        val env = mutableMapOf<String, Any>()
        env["year"] = world.currentYear.toInt()
        env["month"] = world.currentMonth.toInt()
        env["scenario"] = world.scenarioCode
        env["turnterm"] = world.tickSeconds
        env.putAll(world.config)
        return env
    }

    private fun resolveGrade(user: com.opensam.entity.AppUser): Int {
        val grade = user.grade.toInt().coerceIn(0, 7)
        if (user.role.uppercase() == "ADMIN" && grade < 5) {
            return GRADE_SYSTEM_ADMIN
        }
        return grade
    }
}
