package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class 건국(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "건국"

    override val fullConditionConstraints: List<com.opensam.command.constraint.Constraint> by lazy {
        val relYear = env.year - env.startYear
        listOf(
            BeLord(),
            WanderingNation(),
            BeOpeningPart(relYear + 1),
            AllowJoinAction(),
        )
    }

    override val minConditionConstraints: List<com.opensam.command.constraint.Constraint> by lazy {
        val relYear = env.year - env.startYear
        listOf(
            WanderingNation(),
            BeOpeningPart(relYear + 1),
        )
    }

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()

        val initYearMonth = env.startYear * 12 + 1
        val yearMonth = env.year * 12 + env.month
        if (yearMonth <= initYearMonth) {
            pushLog("다음 턴부터 건국할 수 있습니다. <1>$date</>")
            return CommandResult(
                success = false,
                logs = logs,
                message = """{"alternativeCommand":"che_인재탐색"}"""
            )
        }

        val nationName = arg?.get("nationName") as? String ?: "신생국"
        val nationType = arg?.get("nationType") as? String ?: "군벌"
        val colorType = arg?.get("colorType") ?: 0
        val cityName = city?.name ?: "알 수 없음"

        pushLog("<D><b>${nationName}</b></>을 건국하였습니다. <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"experience":1000,"dedication":1000},"nationChanges":{"foundNation":true,"nationName":"$nationName","nationType":"$nationType","colorType":$colorType,"level":1,"capital":${general.cityId}}}"""
        )
    }
}
