package com.opensam.command

import com.opensam.command.constraint.Constraint
import com.opensam.command.constraint.ConstraintContext
import com.opensam.command.constraint.ConstraintResult
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import kotlin.random.Random

abstract class BaseCommand(
    protected val general: General,
    protected val env: CommandEnv,
    protected val arg: Map<String, Any>? = null
) {
    abstract val actionName: String

    protected val logs = mutableListOf<String>()

    protected open val fullConditionConstraints: List<Constraint> = emptyList()
    protected open val minConditionConstraints: List<Constraint> = emptyList()

    var city: City? = null
    var nation: Nation? = null
    var destGeneral: General? = null
    var destCity: City? = null
    var destNation: Nation? = null
    var constraintEnv: Map<String, Any> = emptyMap()

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
