package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class 집합(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "집합"

    override val fullConditionConstraints: List<Constraint> = listOf(
        NotBeNeutral(),
        OccupiedCity(),
        SuppliedCity(),
        MustBeTroopLeader(),
        ReqTroopMembers(),
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val cityName = city?.name ?: "알 수 없음"
        val cityId = city?.id ?: 0L
        val troopName = troop?.name ?: "부대"

        // Legacy: log with city name in color tags
        pushLog("<G><b>${cityName}</b></>에서 집합을 실시했습니다. <1>$date</>")

        val exp = 70
        val ded = 100

        // Legacy: members not at the city get moved + notified
        // The message includes troopLeaderId so the caller can move members
        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"experience":$exp,"dedication":$ded,"leadershipExp":1},"troopAssembly":{"troopLeaderId":"${general.id}","destinationCityId":"$cityId","cityName":"$cityName","troopName":"$troopName","memberNotification":"${troopName} 부대원들은 <G><b>${cityName}</b></>(으)로 집합되었습니다."}}"""
        )
    }
}
