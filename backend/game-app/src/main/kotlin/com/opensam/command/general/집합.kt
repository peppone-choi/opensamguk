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
        val troopId = general.troopId

        pushLog("<G><b>${cityName}</b></>에서 집합을 실시했습니다. <1>$date</>")

        // Legacy: members not at the city get moved + notified
        // PHP: SELECT no FROM general WHERE nation=%i AND city!=%i AND troop=%i AND no!=%i
        //      -> UPDATE general SET city=$cityID WHERE no IN list
        //      -> push notification log for each
        val movedMembers = mutableListOf<General>()
        val generalRepo = services?.generalRepository
        if (generalRepo != null && troopId > 0L) {
            val troopMembers = generalRepo.findByTroopId(troopId)
            for (member in troopMembers) {
                if (member.id != general.id && member.cityId != cityId) {
                    member.cityId = cityId
                    movedMembers.add(member)
                }
            }
            // Save moved members via destCityGenerals hook (CommandExecutor saves this list)
            if (movedMembers.isNotEmpty()) {
                destCityGenerals = movedMembers
            }
        }

        val exp = 70
        val ded = 100

        return CommandResult(
            success = true,
            logs = logs,
            message = buildString {
                append("""{"statChanges":{"experience":$exp,"dedication":$ded,"leadershipExp":1}""")
                append(""","troopAssembly":{""")
                append(""""troopLeaderId":"${general.id}","destinationCityId":"$cityId"""")
                append(""","cityName":"$cityName","troopName":"$troopName"""")
                append(""","movedCount":${movedMembers.size}""")
                append(""","memberNotification":"${troopName} 부대원들은 <G><b>${cityName}</b></>(으)로 집합되었습니다."""")
                append("""}}""")
            }
        )
    }
}
