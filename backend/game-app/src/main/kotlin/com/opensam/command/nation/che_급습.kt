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
        AllowDiplomacyWithTerm(1, 12, "선포 12개월 이상인 상대국에만 가능합니다."),
        AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val dn = destNation ?: return CommandResult(false, logs, "대상 국가 정보를 찾을 수 없습니다")

        n.strategicCmdLimit = STRATEGIC_GLOBAL_DELAY.toShort()

        // Reduce diplomacy term between the two nations (bilateral)
        val diplomacyService = services!!.diplomacyService
        val forwardRels = diplomacyService.getRelationsBetween(env.worldId, n.id, dn.id)
        for (rel in forwardRels) {
            rel.term = max(0, rel.term - TERM_REDUCE).toShort()
        }
        val reverseRels = diplomacyService.getRelationsBetween(env.worldId, dn.id, n.id)
        for (rel in reverseRels) {
            rel.term = max(0, rel.term - TERM_REDUCE).toShort()
        }

        general.experience += 5
        general.dedication += 5

        pushLog("$actionName 발동! <1>$date</>")
        return CommandResult(true, logs)
    }
}
