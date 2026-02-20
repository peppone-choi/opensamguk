package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val POST_REQ_TURN = 12

class che_물자원조(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "원조"

    override val fullConditionConstraints = listOf(
        ExistsDestNation(), OccupiedCity(), BeChief(),
        SuppliedCity(), DifferentDestNation()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = POST_REQ_TURN

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val destNationName = destNation?.name ?: "알 수 없음"
        val explicitGold = (arg?.get("goldAmount") as? Number)?.toInt() ?: 0
        val explicitRice = (arg?.get("riceAmount") as? Number)?.toInt() ?: 0
        val legacyAmount = (arg?.get("amount") as? Number)?.toInt() ?: 0
        val legacyIsGold = arg?.get("isGold") as? Boolean
        val goldAmount = if (explicitGold != 0 || explicitRice != 0) {
            explicitGold
        } else if (legacyIsGold == true) {
            legacyAmount
        } else {
            0
        }
        val riceAmount = if (explicitGold != 0 || explicitRice != 0) {
            explicitRice
        } else if (legacyIsGold == false) {
            legacyAmount
        } else {
            0
        }
        pushLog("<D><b>$destNationName</b></>로 금<C>$goldAmount</> 쌀<C>$riceAmount</>을 지원했습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
