package com.opensam.command

import com.opensam.command.constraint.Constraint
import com.opensam.command.constraint.ConstraintContext
import com.opensam.command.constraint.ConstraintResult
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import com.opensam.entity.Troop
import com.opensam.util.JosaUtil
import kotlin.random.Random

abstract class BaseCommand(
    protected val general: General,
    protected val env: CommandEnv,
    protected val arg: Map<String, Any>? = null
) {
    abstract val actionName: String

    protected val logs = mutableListOf<String>()

    open val canDisplay: Boolean = true
    open val isReservable: Boolean = true

    protected open val fullConditionConstraints: List<Constraint> = emptyList()
    protected open val minConditionConstraints: List<Constraint> = emptyList()
    protected open val permissionConstraints: List<Constraint> = emptyList()

    var city: City? = null
    var nation: Nation? = null
    var destGeneral: General? = null
    var destCity: City? = null
    var destNation: Nation? = null
    var troop: Troop? = null
    var destCityGenerals: List<General>? = null
    var constraintEnv: Map<String, Any> = emptyMap()
    var services: CommandServices? = null

    abstract fun getCost(): CommandCost
    open fun getCommandPointCost(): Int = 1
    abstract fun getPreReqTurn(): Int
    abstract fun getPostReqTurn(): Int
    abstract suspend fun run(rng: Random): CommandResult

    /** Duration in seconds for realtime mode. Default 300s (5min). */
    open fun getDuration(): Int = 300

    open fun getAlternativeCommand(): String? = null

    fun checkFullCondition(): ConstraintResult {
        val ctx = ConstraintContext(
            general = general,
            city = city,
            nation = nation,
            destGeneral = destGeneral,
            destCity = destCity,
            destNation = destNation,
            arg = arg,
            env = buildConstraintContextEnv(),
        )
        for (constraint in fullConditionConstraints) {
            val result = constraint.test(ctx)
            if (result is ConstraintResult.Fail) return result
        }
        return ConstraintResult.Pass
    }

    fun checkMinCondition(): ConstraintResult {
        val ctx = ConstraintContext(
            general = general,
            city = city,
            nation = nation,
            arg = arg,
            env = buildConstraintContextEnv(),
        )
        for (constraint in minConditionConstraints) {
            val result = constraint.test(ctx)
            if (result is ConstraintResult.Fail) return result
        }
        return ConstraintResult.Pass
    }

    protected fun pushLog(message: String) {
        logs.add(message)
    }

    protected fun formatDate(): String {
        val monthStr = env.month.toString().padStart(2, '0')
        return "${env.year}년 ${monthStr}월"
    }

    // ========== Korean josa helpers ==========

    /**
     * Pick the correct Korean postposition (조사) for the given word.
     */
    protected fun pickJosa(word: String, josa: String): String = JosaUtil.pick(word, josa)

    /**
     * Alias for pickJosa - returns the josa string for the given word.
     */
    protected fun josa(word: String, josa: String): String = JosaUtil.pick(word, josa)

    // ========== Log helpers ==========

    /**
     * Push a log entry tagged as a general history log.
     */
    protected fun pushHistoryLog(message: String) {
        logs.add("_history:$message")
    }

    /**
     * Push a log entry tagged as a global action log (visible to all players).
     */
    protected fun pushGlobalLog(message: String) {
        logs.add("_global:$message")
    }

    /**
     * Push a log entry tagged as a global action log.
     */
    protected fun pushGlobalActionLog(message: String) {
        logs.add("_globalAction:$message")
    }

    /**
     * Push a log entry tagged as a global history log.
     */
    protected fun pushGlobalHistoryLog(message: String) {
        logs.add("_globalHistory:$message")
    }

    /**
     * Push a log entry tagged as an own national history log.
     */
    protected fun pushNationalHistoryLog(message: String) {
        logs.add("_nationalHistory:$message")
    }

    /**
     * Push a log entry tagged as a dest nation history log (using destNation).
     */
    protected fun pushDestNationalHistoryLog(message: String) {
        val dnId = destNation?.id ?: 0L
        logs.add("_destNationalHistory:${dnId}:$message")
    }

    /**
     * Push a log entry tagged as a dest nation history log for a specific nation ID.
     */
    protected fun pushDestNationalHistoryLogFor(nationId: Long, message: String) {
        logs.add("_destNationalHistory:${nationId}:$message")
    }

    /**
     * Push a log entry for the dest general.
     */
    protected fun pushDestGeneralLog(message: String) {
        val dgId = destGeneral?.id ?: 0L
        logs.add("_destGeneralLog:${dgId}:$message")
    }

    /**
     * Push a history log entry for the dest general.
     */
    protected fun pushDestGeneralHistoryLog(message: String) {
        val dgId = destGeneral?.id ?: 0L
        logs.add("_destGeneralHistory:${dgId}:$message")
    }

    /**
     * Broadcast a message to all generals of a specific nation.
     * @param nationId The nation to broadcast to
     * @param excludeGeneralId Optional general ID to exclude from broadcast
     * @param message The message to broadcast
     */
    protected fun broadcastToNationGenerals(nationId: Long, excludeGeneralId: Long?, message: String) {
        logs.add("_broadcast:${nationId}:${excludeGeneralId ?: ""}:$message")
    }

    // ========== Game data helpers ==========

    /**
     * Get nation tech cost multiplier (legacy parity).
     * Returns a cost multiplier based on nation's tech level.
     */
    protected fun getNationTechCost(): Double {
        val tech = nation?.tech?.toDouble() ?: 0.0
        return 1.0 + tech / 1000.0
    }

    /**
     * Get the term value from the general's lastTurn data (for multi-turn commands).
     */
    protected fun getLastTurnTerm(): Int {
        return (general.lastTurn["term"] as? Number)?.toInt() ?: 0
    }

    /**
     * Get BFS distance from the general's city to a target city using the map adjacency.
     * Returns null if unreachable.
     */
    protected fun getDistanceTo(targetCityId: Long): Int? {
        val adjacencyRaw = env.gameStor["mapAdjacency"]
        if (adjacencyRaw !is Map<*, *>) return null

        val startCityId = general.cityId
        if (startCityId == targetCityId) return 0

        val adjacency = mutableMapOf<Long, List<Long>>()
        adjacencyRaw.forEach { (k, v) ->
            val key = when (k) {
                is Number -> k.toLong()
                is String -> k.toLongOrNull()
                else -> null
            } ?: return@forEach
            val values = (v as? Iterable<*>)?.mapNotNull { elem ->
                when (elem) {
                    is Number -> elem.toLong()
                    is String -> elem.toLongOrNull()
                    else -> null
                }
            } ?: emptyList()
            adjacency[key] = values
        }

        val visited = mutableSetOf(startCityId)
        val queue = ArrayDeque<Pair<Long, Int>>()
        queue.addLast(startCityId to 0)

        while (queue.isNotEmpty()) {
            val (cityId, distance) = queue.removeFirst()
            for (next in adjacency[cityId].orEmpty()) {
                if (next == targetCityId) return distance + 1
                if (!visited.add(next)) continue
                queue.addLast(next to (distance + 1))
            }
        }

        return null
    }

    /**
     * Get the number of generals in the dest city.
     */
    protected fun getDestCityGeneralCount(): Int {
        return destCityGenerals?.size ?: 0
    }

    /**
     * Get total crew count of all generals in the dest city.
     */
    protected fun getDestCityTotalCrew(): Int {
        return destCityGenerals?.sumOf { it.crew } ?: 0
    }

    /**
     * Get a text summary of crew types in the dest city.
     */
    protected fun getDestCityCrewTypeSummary(): String {
        val generals = destCityGenerals ?: return ""
        val grouped = generals.filter { it.crew > 0 }.groupBy { it.crewType.toInt() }
        if (grouped.isEmpty()) return ""
        return grouped.entries.joinToString(", ") { (typeId, gens) ->
            val typeName = getCrewTypeName(typeId) ?: "병종$typeId"
            val total = gens.sumOf { it.crew }
            "${typeName}:${String.format("%,d", total)}"
        }
    }

    /**
     * Get crew type name from the environment game storage.
     */
    protected fun getCrewTypeName(crewTypeId: Int): String? {
        @Suppress("UNCHECKED_CAST")
        val crewTypes = env.gameStor["crewTypes"] as? Map<String, Any> ?: return null
        val entry = crewTypes[crewTypeId.toString()] as? Map<*, *> ?: return null
        return entry["name"] as? String
    }

    private fun buildConstraintContextEnv(): Map<String, Any> {
        val merged = mutableMapOf<String, Any>(
            "worldId" to env.worldId,
            "year" to env.year,
            "month" to env.month,
            "startYear" to env.startYear,
            "realtimeMode" to env.realtimeMode,
        )
        merged.putAll(env.gameStor)
        merged.putAll(constraintEnv)
        return merged
    }
}
