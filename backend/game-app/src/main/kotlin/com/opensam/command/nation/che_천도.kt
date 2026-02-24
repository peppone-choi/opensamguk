package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.LastTurn
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.math.pow
import kotlin.random.Random

/**
 * 천도 (Capital relocation) — Legacy parity: che_천도.php
 *
 * Multi-turn command: getPreReqTurn() = distance * 2
 * The CommandExecutor handles addTermStack automatically, but legacy 천도
 * has extra reset logic based on `capset` (incremented whenever capital/증축 changes).
 * We track capset in nation.meta["capSet"] and compare with the stored seq in lastTurn.
 *
 * On completion:
 *  - Update nation.capitalCityId
 *  - Increment nation.meta["capSet"]
 *  - Award experience + dedication = 5 * (preReqTurn + 1)
 *  - Award inheritancePoint(active_action, 1)
 *  - refreshNationStaticInfo (handled by engine on save)
 */
class che_천도(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "천도"

    private var cachedDist: Int? = null

    private fun getDistance(): Int {
        cachedDist?.let { return it }

        val capitalCityId = nation?.capitalCityId ?: return 2
        val destCityId = (arg?.get("destCityID") as? Number)?.toLong()
            ?: (arg?.get("destCityId") as? Number)?.toLong()
            ?: destCity?.id ?: return 2

        if (capitalCityId == destCityId) {
            cachedDist = 0
            return 0
        }

        // BFS distance from capital to destCity through own-nation territory
        val dist = computeDistanceThroughOwnTerritory(capitalCityId, destCityId)
        cachedDist = dist ?: 50
        return cachedDist!!
    }

    private fun computeDistanceThroughOwnTerritory(srcCityId: Long, destCityId: Long): Int? {
        @Suppress("UNCHECKED_CAST")
        val adjacencyRaw = constraintEnv["mapAdjacency"] as? Map<*, *> ?: return null
        @Suppress("UNCHECKED_CAST")
        val cityNationById = constraintEnv["cityNationById"] as? Map<*, *>

        val nationId = general.nationId

        val adjacency = mutableMapOf<Long, List<Long>>()
        adjacencyRaw.forEach { (k, v) ->
            val key = when (k) {
                is Number -> k.toLong()
                is String -> k.toLongOrNull()
                else -> null
            } ?: return@forEach
            val values = (v as? Iterable<*>)?.mapNotNull { elem ->
                when (elem) {
                    is Number -> elem.toLong()
                    is String -> elem.toLongOrNull()
                    else -> null
                }
            } ?: emptyList()
            adjacency[key] = values
        }

        val visited = mutableSetOf(srcCityId)
        val queue = ArrayDeque<Pair<Long, Int>>()
        queue.addLast(srcCityId to 0)

        while (queue.isNotEmpty()) {
            val (curCity, dist) = queue.removeFirst()
            for (nextCity in adjacency[curCity].orEmpty()) {
                if (!visited.add(nextCity)) continue
                val nextDist = dist + 1
                if (nextCity == destCityId) return nextDist

                // Only traverse through own-nation cities
                if (cityNationById != null) {
                    val nextNation = when (val raw = cityNationById[nextCity.toString()] ?: cityNationById[nextCity]) {
                        is Number -> raw.toLong()
                        else -> 0L
                    }
                    if (nextNation != nationId) continue
                }
                queue.addLast(nextCity to nextDist)
            }
        }
        return null
    }

    override val fullConditionConstraints: List<Constraint>
        get() {
            val dist = getDistance()
            if (dist == 50) {
                // Unreachable
                return listOf(AlwaysFail("천도 대상으로 도달할 방법이 없습니다."))
            }
            val cost = getCostAmount()
            val baseGold = (env.gameStor["baseGold"] as? Number)?.toInt() ?: 1000
            val baseRice = (env.gameStor["baseRice"] as? Number)?.toInt() ?: 1000
            return listOf(
                OccupiedCity(),
                OccupiedDestCity(),
                BeChief(),
                SuppliedCity(),
                SuppliedDestCity(),
                ReqNationGold(baseGold + cost),
                ReqNationRice(baseRice + cost),
            )
        }

    private fun getCostAmount(): Int {
        val dist = getDistance()
        return (env.develCost * 5 * 2.0.pow(dist)).toInt()
    }

    override fun getCost(): CommandCost {
        val amount = getCostAmount()
        return CommandCost(gold = amount, rice = amount)
    }

    override fun getPreReqTurn() = getDistance() * 2
    override fun getPostReqTurn() = 0

    /**
     * Legacy parity for multi-turn with capSet reset.
     *
     * In legacy PHP, 천도.addTermStack() checks:
     *  1. If lastTurn command/arg differs → reset to term=1
     *  2. If lastTurn.seq < nation.capset → reset to term=1 (capset changed since start)
     *  3. If lastTurn.term < preReqTurn → increment term
     *  4. Otherwise → ready to execute
     *
     * The CommandExecutor calls LastTurn.addTermStack() generically, which handles #1 and #3.
     * We override the run() method to check #2 (capSet invalidation) before executing.
     *
     * We encode the capSet in the lastTurn's term metadata via nation.meta.
     */
    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val dCity = destCity ?: return CommandResult(false, logs, "대상 도시 정보를 찾을 수 없습니다")
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")

        // Check not already capital
        if (n.capitalCityId == dCity.id) {
            return CommandResult(false, logs, "이미 수도입니다.")
        }

        // capSet invalidation check (legacy parity)
        val currentCapSet = (n.meta["capSet"] as? Number)?.toInt() ?: 0
        val lastTurn = LastTurn.fromMap(general.lastTurn)
        val storedCapSet = (general.lastTurn["capSetSeq"] as? Number)?.toInt() ?: -1

        if (storedCapSet != currentCapSet) {
            // capSet changed since we started accumulating turns → must restart
            // Store new capSet and reset term
            general.lastTurn = mutableMapOf(
                "command" to actionName,
                "arg" to (arg ?: emptyMap<String, Any>()),
                "term" to 1,
                "capSetSeq" to currentCapSet,
            )
            val preReq = getPreReqTurn()
            pushLog("${actionName} 수행중... (1/${preReq})")
            return CommandResult(
                success = true,
                logs = logs,
            )
        }

        // Record last천도Trial in nation meta (legacy: nationStor->last천도Trial)
        n.meta["last_chundo_trial"] = mapOf(
            "officerLevel" to general.officerLevel.toInt(),
            "turnTime" to general.turnTime.toString(),
        )

        // Apply costs
        val cost = getCost()
        n.gold -= cost.gold
        n.rice -= cost.rice

        // Update capital
        n.capitalCityId = dCity.id

        // Increment capSet
        n.meta["capSet"] = currentCapSet + 1

        // Experience/dedication: 5 * (preReqTurn + 1)
        val expDed = 5 * (getPreReqTurn() + 1)
        general.experience += expDed
        general.dedication += expDed

        // Inheritance point
        val inheritMeta = general.meta.getOrPut("inheritancePoints") { mutableMapOf<String, Any>() }
        @Suppress("UNCHECKED_CAST")
        if (inheritMeta is MutableMap<*, *>) {
            (inheritMeta as MutableMap<String, Any>)["active_action"] =
                ((inheritMeta["active_action"] as? Number)?.toInt() ?: 0) + 1
        }

        // Logging (legacy parity)
        val generalName = general.name
        val nationName = n.name
        val josaRo = JosaUtil.pick(dCity.name, "로")
        val josaYi = JosaUtil.pick(generalName, "이")
        val josaYiNation = JosaUtil.pick(nationName, "이")

        pushLog("<G><b>${dCity.name}</b></>${josaRo} 천도했습니다. <1>$date</>")
        pushHistoryLog("<G><b>${dCity.name}</b></>${josaRo} <M>천도</>명령")
        pushNationalHistoryLog("<Y>${generalName}</>${josaYi} <G><b>${dCity.name}</b></>${josaRo} <M>천도</> 명령")
        pushGlobalActionLog("<Y>${generalName}</>${josaYi} <G><b>${dCity.name}</b></>${josaRo} <M>천도</>를 명령하였습니다.")
        pushGlobalHistoryLog("<S><b>【천도】</b></><D><b>${nationName}</b></>${josaYiNation} <G><b>${dCity.name}</b></>${josaRo} <M>천도</>하였습니다.")

        // Reset lastTurn term to 0 (completed)
        general.lastTurn = LastTurn(actionName, arg, 0).toMap()

        return CommandResult(true, logs)
    }
}
