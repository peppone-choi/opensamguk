package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
import kotlin.random.Random

private const val STRATEGIC_GLOBAL_DELAY = 9
private const val TERM_REDUCE = 3

class che_급습(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "급습"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), ExistsDestNation(),
        AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 40

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val dn = destNation ?: return CommandResult(false, logs, "대상 국가 정보를 찾을 수 없습니다")

        n.strategicCmdLimit = STRATEGIC_GLOBAL_DELAY.toShort()

        val diplomacyService = services!!.diplomacyService
        val relations = diplomacyService.getRelationsForNation(env.worldId, dn.id)
        for (rel in relations) {
            if (rel.stateCode == "불가침") {
                rel.term = max(0, rel.term - TERM_REDUCE).toShort()
            }
        }

        general.experience += 50
        general.dedication += 50

        pushLog("$actionName 발동! <1>$date</>")
        return CommandResult(true, logs)
    }
}
