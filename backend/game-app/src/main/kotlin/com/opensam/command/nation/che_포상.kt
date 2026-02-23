package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class che_포상(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "포상"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val destGeneralId = (arg?.get("destGeneralID") as? Number)?.toLong() ?: 0L
            if (destGeneralId == general.id) {
                return listOf(AlwaysFail("본인입니다"))
            }

            val isGold = arg?.get("isGold") as? Boolean ?: true
            val baseGold = (env.gameStor["baseGold"] as? Number)?.toInt() ?: 1000
            val baseRice = (env.gameStor["baseRice"] as? Number)?.toInt() ?: 1000

            val resourceConstraint = if (isGold) {
                ReqNationGold(1 + baseGold)
            } else {
                ReqNationRice(1 + baseRice)
            }

            return listOf(
                NotBeNeutral(),
                OccupiedCity(),
                BeChief(),
                SuppliedCity(),
                ExistsDestGeneral(),
                FriendlyDestGeneral(),
                resourceConstraint,
            )
        }

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val destGen = destGeneral ?: return CommandResult(false, logs, "대상 장수 정보를 찾을 수 없습니다")
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")

        val isGold = arg?.get("isGold") as? Boolean ?: true
        val maxAmount = (env.gameStor["maxResourceActionAmount"] as? Number)?.toInt() ?: 100000
        var amount = ((arg?.get("amount") as? Number)?.toInt() ?: 100)
        // Round to nearest 100
        amount = (Math.round(amount.toDouble() / 100) * 100).toInt()
        amount = amount.coerceIn(100, maxAmount)

        val baseGold = (env.gameStor["baseGold"] as? Number)?.toInt() ?: 1000
        val baseRice = (env.gameStor["baseRice"] as? Number)?.toInt() ?: 1000

        val resKey = if (isGold) "gold" else "rice"
        val resName = if (isGold) "금" else "쌀"
        val base = if (isGold) baseGold else baseRice
        val available = if (isGold) n.gold - base else n.rice - base
        amount = amount.coerceIn(0, available)

        if (amount <= 0) {
            return CommandResult(false, logs, "${resName}이(가) 부족합니다")
        }

        if (isGold) {
            n.gold -= amount
            destGen.gold += amount
        } else {
            n.rice -= amount
            destGen.rice += amount
        }

        val amountText = String.format("%,d", amount)
        pushLog("<Y>${destGen.name}</>에게 $resName <C>$amountText</>을 수여했습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
