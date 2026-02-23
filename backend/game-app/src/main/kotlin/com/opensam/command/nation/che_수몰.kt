package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

private const val INITIAL_NATION_GEN_LIMIT = 10
private const val STRATEGIC_GLOBAL_DELAY = 9
private const val PRE_REQ_TURN = 2
private const val DAMAGE_RATE = 0.2

class che_수몰(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "수몰"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(),
        NotNeutralDestCity(), NotOccupiedDestCity(),
        BattleGroundCity(), AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = PRE_REQ_TURN

    override fun getPostReqTurn(): Int {
        val genCount = max(nation?.gennum ?: 1, INITIAL_NATION_GEN_LIMIT)
        return (sqrt(genCount * 4.0) * 10).roundToInt()
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val dc = destCity ?: return CommandResult(false, logs, "대상 도시 정보를 찾을 수 없습니다")

        val destNationId = dc.nationId
        val destNationName = services!!.nationRepository.findById(destNationId)?.name ?: "알수없음"
        val generalName = general.name
        val nationName = n.name

        val josaYi = JosaUtil.pick(generalName, "이")
        val josaYiNation = JosaUtil.pick(nationName, "이")

        // Experience and dedication: 5 * (preReqTurn + 1)
        val expDed = 5 * (PRE_REQ_TURN + 1)
        general.experience += expDed
        general.dedication += expDed

        pushLog("수몰 발동! <1>$date</>")

        val broadcastMessage = "<Y>${generalName}</>${josaYi} <G><b>${dc.name}</b></>에 <M>수몰</>을 발동하였습니다."

        // Broadcast to own nation generals
        broadcastToNationGenerals(n.id, general.id, broadcastMessage)

        // Broadcast to dest nation generals
        val destBroadcastMessage = "<G><b>${dc.name}</b></>에 <M>수몰</>이 발동되었습니다."
        broadcastToNationGenerals(destNationId, null, destBroadcastMessage)

        // Dest nation history
        pushDestNationalHistoryLogFor(destNationId,
            "<D><b>${nationName}</b></>의 <Y>${generalName}</>${josaYi} 아국의 <G><b>${dc.name}</b></>에 <M>수몰</>을 발동")

        // Reduce def and wall to 20%
        dc.def = (dc.def * DAMAGE_RATE).toInt()
        dc.wall = (dc.wall * DAMAGE_RATE).toInt()

        // General history + national history
        pushHistoryLog("<G><b>${dc.name}</b></>에 <M>수몰</>을 발동")
        pushNationalHistoryLog("<Y>${generalName}</>${josaYi} <G><b>${dc.name}</b></>에 <M>수몰</>을 발동")

        // Strategic command limit
        n.strategicCmdLimit = STRATEGIC_GLOBAL_DELAY

        return CommandResult(true, logs)
    }
}
