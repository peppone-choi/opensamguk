package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val INITIAL_NATION_GEN_LIMIT = 8

class 장수대상임관(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "장수를 따라 임관"

    override val permissionConstraints: List<Constraint>
        get() = listOf(
            ReqEnvValue("join_mode", "!=", "onlyRandom", "랜덤 임관만 가능합니다"),
        )

    override val fullConditionConstraints: List<Constraint>
        get() = listOf(
            ReqEnvValue("join_mode", "!=", "onlyRandom", "랜덤 임관만 가능합니다"),
            BeNeutral(),
            ExistsDestNation(),
            AllowJoinDestNation(),
            AllowJoinAction(),
        )

    override val minConditionConstraints: List<Constraint>
        get() = listOf(
            ReqEnvValue("join_mode", "!=", "onlyRandom", "랜덤 임관만 가능합니다"),
            BeNeutral(),
            AllowJoinAction(),
        )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val dn = destNation!!
        val destNationName = dn.name
        val destCityId = destGeneral?.cityId ?: dn.capitalCityId ?: 0L
        val generalName = general.name

        pushLog("<D>${destNationName}</>에 임관했습니다. <1>$date</>")
        pushHistoryLog("<D><b>${destNationName}</b></>에 임관")
        pushGlobalLog("<Y>${generalName}</> <D><b>${destNationName}</b></>에 <S>임관</>했습니다.")

        // Legacy parity: gennum < initialNationGenLimit → exp 700, else 100
        val gennum = services?.generalRepository?.findByNationId(dn.id)?.size ?: 0
        val exp = if (gennum < INITIAL_NATION_GEN_LIMIT) 700 else 100

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"nation":${dn.id},"officerLevel":1,"officerCity":0,"belong":1,"city":$destCityId,"experience":$exp},"nationChanges":{"nationId":${dn.id},"gennum":1}}"""
        )
    }
}
