package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

private const val INITIAL_NATION_GEN_LIMIT = 10
private const val STRATEGIC_GLOBAL_DELAY = 9
private const val PRE_REQ_TURN = 0

class che_이호경식(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "이호경식"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), ExistsDestNation(),
        AllowDiplomacyBetweenStatus(listOf(0, 1), "선포, 전쟁중인 상대국에게만 가능합니다."),
        AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = PRE_REQ_TURN

    override fun getPostReqTurn(): Int {
        val genCount = max(nation?.gennum ?: 1, INITIAL_NATION_GEN_LIMIT)
        return (sqrt(genCount * 16.0) * 10).roundToInt()
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val dn = destNation ?: return CommandResult(false, logs, "대상 국가 정보를 찾을 수 없습니다")

        val generalName = general.name
        val nationName = n.name
        val destNationName = dn.name

        val josaYi = JosaUtil.pick(generalName, "이")
        val josaYiNation = JosaUtil.pick(nationName, "이")
        val josaUl = JosaUtil.pick(actionName, "을")

        // Experience and dedication: 5 * (preReqTurn + 1)
        val expDed = 5 * (PRE_REQ_TURN + 1)
        general.experience += expDed
        general.dedication += expDed

        pushLog("$actionName 발동! <1>$date</>")

        // Broadcast to own nation generals
        val broadcastMessage = "<Y>${generalName}</>${josaYi} <G><b>${destNationName}</b></>에 <M>${actionName}</>${josaUl} 발동하였습니다."
        broadcastToNationGenerals(n.id, general.id, broadcastMessage)

        // Broadcast to dest nation generals
        val destBroadcastMessage = "<D><b>${nationName}</b></>${josaYiNation} 아국에 <M>${actionName}</>${josaUl} 발동하였습니다."
        broadcastToNationGenerals(dn.id, null, destBroadcastMessage)

        // Dest nation history
        pushDestNationalHistoryLogFor(dn.id,
            "<D><b>${nationName}</b></>의 <Y>${generalName}</>${josaYi} 아국에 <M>${actionName}</>${josaUl} 발동")

        // General and nation history
        pushHistoryLog("<D><b>${destNationName}</b></>에 <M>${actionName}</>${josaUl} 발동")
        pushNationalHistoryLog("<Y>${generalName}</>${josaYi} <D><b>${destNationName}</b></>에 <M>${actionName}</>${josaUl} 발동")

        // Strategic command limit
        n.strategicCmdLimit = STRATEGIC_GLOBAL_DELAY

        // Update diplomacy: force to state=1 (declaration), term logic depends on current state
        // If currently at war (state=0), term becomes 3; otherwise term += 3
        val currentDiplomacy = services!!.diplomacyService.getDiplomacyState(env.worldId, n.id, dn.id)
        val newTerm = if (currentDiplomacy?.state == 0) 3 else (currentDiplomacy?.term ?: 0) + 3
        services!!.diplomacyService.setDiplomacyState(env.worldId, n.id, dn.id, state = 1, term = newTerm)

        // Update nation fronts
        services!!.nationService.setNationFront(env.worldId, n.id)
        services!!.nationService.setNationFront(env.worldId, dn.id)

        return CommandResult(true, logs)
    }
}
