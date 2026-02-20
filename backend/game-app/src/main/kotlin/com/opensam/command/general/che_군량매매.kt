package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.roundToInt
import kotlin.random.Random

private const val MAX_RESOURCE_ACTION_AMOUNT = 10000
private const val EXCHANGE_FEE = 0.03

class che_군량매매(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "군량매매"

    private val buyRice: Boolean
        get() = arg?.get("buyRice") as? Boolean ?: true

    private val tradeAmount: Int
        get() {
            val raw = (arg?.get("amount") as? Number)?.toInt() ?: 100
            val rounded = (raw / 100) * 100
            return maxOf(100, minOf(rounded, MAX_RESOURCE_ACTION_AMOUNT))
        }

    override val fullConditionConstraints: List<Constraint>
        get() {
            val constraints = mutableListOf<Constraint>(
                OccupiedCity(),
                SuppliedCity()
            )
            if (buyRice) {
                constraints.add(ReqGeneralGold(1))
            } else {
                constraints.add(ReqGeneralRice(1))
            }
            return constraints
        }

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0
    override fun getDuration() = 300

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val isBuyRice = buyRice
        val argAmount = tradeAmount

        val tradeRate = (city?.trade ?: 100) / 100.0
        val currentGold = general.gold
        val currentRice = general.rice

        val buyAmount: Double
        val sellAmount: Double
        val tax: Double

        if (isBuyRice) {
            var sell = minOf(argAmount * tradeRate, currentGold.toDouble())
            var t = sell * EXCHANGE_FEE
            if (sell + t > currentGold) {
                sell *= currentGold / (sell + t)
                t = currentGold - sell
            }
            buyAmount = sell / tradeRate
            sellAmount = sell + t
            tax = t
            pushLog("군량 ${buyAmount.roundToInt()}을 사서 자금 ${sellAmount.roundToInt()}을 썼습니다. $date")
        } else {
            val sell = minOf(argAmount.toDouble(), currentRice.toDouble())
            var buy = sell * tradeRate
            val t = buy * EXCHANGE_FEE
            buy -= t
            buyAmount = buy
            sellAmount = sell
            tax = t
            pushLog("군량 ${sellAmount.roundToInt()}을 팔아 자금 ${buyAmount.roundToInt()}을 얻었습니다. $date")
        }

        // random stat exp weighted by stats
        val leadership = general.leadership.toInt()
        val strength = general.strength.toInt()
        val intel = general.intel.toInt()
        val statWeights = listOf(
            "leadershipExp" to leadership,
            "strengthExp" to strength,
            "intelExp" to intel
        )
        val totalWeight = statWeights.sumOf { it.second }
        var roll = rng.nextInt(totalWeight).toDouble()
        var incStat = "leadershipExp"
        for ((key, weight) in statWeights) {
            roll -= weight
            if (roll < 0) { incStat = key; break }
        }

        val goldDelta = if (isBuyRice) -sellAmount.roundToInt() else buyAmount.roundToInt()
        val riceDelta = if (isBuyRice) buyAmount.roundToInt() else -sellAmount.roundToInt()

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":$goldDelta,"rice":$riceDelta,"experience":30,"dedication":50,"$incStat":1},"nationTax":${tax.roundToInt()}}"""
        )
    }
}
