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
        SuppliedCity(), DifferentDestNation(),
        ReqNationValue("surlimit", "외교제한", "==", 0, "외교제한중입니다."),
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = POST_REQ_TURN

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val dn = destNation ?: return CommandResult(false, logs, "대상 국가 정보를 찾을 수 없습니다")

        // Parse amountList [goldAmount, riceAmount] matching PHP/TS
        @Suppress("UNCHECKED_CAST")
        val amountList = arg?.get("amountList") as? List<Number>
        val goldAmount: Int
        val riceAmount: Int
        if (amountList != null && amountList.size == 2) {
            goldAmount = amountList[0].toInt()
            riceAmount = amountList[1].toInt()
        } else {
            goldAmount = (arg?.get("goldAmount") as? Number)?.toInt() ?: 0
            riceAmount = (arg?.get("riceAmount") as? Number)?.toInt() ?: 0
        }

        if (goldAmount <= 0 && riceAmount <= 0) {
            return CommandResult(false, logs, "원조 금액이 없습니다")
        }

        val actualGold = goldAmount.coerceIn(0, n.gold)
        val actualRice = riceAmount.coerceIn(0, n.rice)

        n.gold -= actualGold
        n.rice -= actualRice
        dn.gold += actualGold
        dn.rice += actualRice

        // Set surlimit (diplomacy cooldown)
        val currentSurlimit = (n.meta["surlimit"] as? Number)?.toInt() ?: 0
        n.meta["surlimit"] = currentSurlimit + POST_REQ_TURN

        general.experience += 5
        general.dedication += 5

        pushLog("<D><b>${dn.name}</b></>로 금<C>$actualGold</> 쌀<C>$actualRice</>을 지원했습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
