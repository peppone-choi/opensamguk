package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class CR건국(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "건국"

    override val fullConditionConstraints: List<Constraint> by lazy {
        val relYear = env.year - env.startYear
        listOf(
            BeLord(),
            WanderingNation(),
            BeOpeningPart(relYear + 1),
            AllowJoinAction(),
            NeutralCity(),
        )
    }

    override val minConditionConstraints: List<Constraint> by lazy {
        val relYear = env.year - env.startYear
        listOf(
            BeOpeningPart(relYear + 1),
        )
    }

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()

        val nationName = arg?.get("nationName") as? String
            ?: return CommandResult(success = false, logs = listOf("인자가 없습니다."))
        val nationType = arg?.get("nationType") as? String ?: "군벌"
        val colorType = arg?.get("colorType") ?: 0
        val cityName = city?.name ?: "알 수 없는 도시"

        pushLog("<D><b>${nationName}</b></>을(를) 건국하였습니다. <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"experience":1000,"dedication":1000},"nationFoundation":{"name":"$nationName","type":"$nationType","colorType":$colorType,"capitalCityId":${general.cityId}}}"""
        )
    }
}
