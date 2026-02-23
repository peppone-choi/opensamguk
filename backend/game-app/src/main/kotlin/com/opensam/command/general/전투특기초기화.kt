package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

open class 전투특기초기화(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "전투 특기 초기화"

    protected open val specialField: String = "special2Code"
    protected open val specialText: String = "전투 특기"
    protected open val specAgeField: String = "specAge2"

    override val fullConditionConstraints: List<Constraint> by lazy {
        listOf(
            ReqGeneralStatValue({ g ->
                val value = when (specialField) {
                    "specialCode" -> g.specialCode
                    "special2Code" -> g.special2Code
                    else -> "None"
                }
                if (value != "None") 1 else 0
            }, specialText, 1),
        )
    }

    override val minConditionConstraints get() = fullConditionConstraints

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 1
    override fun getPostReqTurn() = 60

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()

        pushLog("새로운 ${specialText}를 가질 준비가 되었습니다. <1>$date</>")

        val currentAge = general.age.toInt()

        // Track previous specials to avoid re-rolls (legacy: prev_types_special2)
        val prevTypesKey = "prev_types_$specialField"
        val currentSpecial = when (specialField) {
            "specialCode" -> general.specialCode
            "special2Code" -> general.special2Code
            else -> "None"
        }

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"$specialField":"None","$specAgeField":${currentAge + 1}},"specialReset":{"type":"$specialField","prevTypesKey":"$prevTypesKey","oldSpecial":"$currentSpecial"}}"""
        )
    }
}
