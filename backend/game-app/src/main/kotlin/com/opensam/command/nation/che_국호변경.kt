package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class che_국호변경(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "국호변경"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), SuppliedCity(),
        ReqNationAuxValue("can_국호변경", 0, ">", 0, "더이상 변경이 불가능합니다.")
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val newName = (arg?.get("nationName") as? String)
            ?: return CommandResult(false, logs, "국호가 지정되지 않았습니다")
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")

        if (newName.isBlank() || newName.length > 8) {
            return CommandResult(false, logs, "유효하지 않은 국호입니다")
        }

        // Check duplicate nation name
        val existingNation = services!!.nationRepository.findByWorldIdAndName(env.worldId, newName)
        if (existingNation != null) {
            pushLog("이미 같은 국호를 가진 곳이 있습니다. 국호변경 실패 <1>$date</>")
            return CommandResult(false, logs, "이미 같은 국호를 가진 곳이 있습니다")
        }

        n.name = newName
        n.meta["can_국호변경"] = 0

        general.experience += 5
        general.dedication += 5

        pushLog("국호를 <D><b>$newName</b></>으로 변경합니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
