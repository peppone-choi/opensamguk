package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.math.roundToInt
import kotlin.random.Random

private const val INITIAL_NATION_GEN_LIMIT = 10
private const val STRATEGIC_GLOBAL_DELAY = 9
private const val PRE_REQ_TURN = 2
private const val NPC_TYPE = 4

class che_의병모집(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "의병모집"

    override val fullConditionConstraints = listOf(
        BeChief(), NotBeNeutral(), OccupiedCity(),
        AvailableStrategicCommand(),
        NotOpeningPart(env.year - env.startYear)
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = PRE_REQ_TURN

    override fun getPostReqTurn(): Int {
        return 100
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val c = city ?: return CommandResult(false, logs, "도시 정보를 찾을 수 없습니다")

        val generalName = general.name
        val nationName = n.name
        val josaYi = JosaUtil.pick(generalName, "이")
        val josaYiNation = JosaUtil.pick(nationName, "이")
        val josaUl = JosaUtil.pick(actionName, "을")

        // Experience and dedication: 5 * (preReqTurn + 1)
        val expDed = 5 * (PRE_REQ_TURN + 1)
        general.experience += expDed
        general.dedication += expDed

        pushLog("$actionName 발동! <1>$date</>")

        // Broadcast to own nation generals
        val broadcastMessage = "<Y>${generalName}</>${josaYi} <M>${actionName}</>${josaUl} 발동하였습니다."
        broadcastToNationGenerals(n.id, general.id, broadcastMessage)

        // History logs
        pushHistoryLog("<M>${actionName}</>${josaUl} 발동")
        pushNationalHistoryLog("<Y>${generalName}</>${josaYi} <M>${actionName}</>${josaUl} 발동")

        // Calculate NPC count: 3 + round(avgGenCount / 8)
        val avgGenCount = services!!.nationRepository.getAverageGennum(env.worldId)
        val createGenCount = 3 + (avgGenCount / 8.0).roundToInt()

        // Get nation average stats for initial exp/ded
        val avgStats = services!!.generalRepository.getAverageStats(env.worldId, n.id)

        // Create NPCs from general pool
        for (i in 1..createGenCount) {
            val npc = services!!.generalPoolService?.pickAndCreateNpc(
                worldId = env.worldId,
                nationId = n.id,
                cityId = c.id,
                npcType = NPC_TYPE,
                birthYear = env.year - 20,
                deathYear = env.year + 10,
                killTurn = rng.nextInt(64, 71),
                gold = 1000,
                rice = 1000,
                experience = avgStats.experience,
                dedication = avgStats.dedication,
                specAge = 19,
                rng = rng
            )
        }

        // Update nation gennum and strategic limit
        n.gennum = n.gennum + createGenCount
        n.strategicCmdLimit = STRATEGIC_GLOBAL_DELAY.toShort()

        return CommandResult(true, logs)
    }
}
