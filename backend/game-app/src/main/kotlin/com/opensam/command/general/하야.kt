package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

private const val DEFAULT_GOLD = 1000
private const val DEFAULT_RICE = 1000
private const val MAX_BETRAY_COUNT = 10

class 하야(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "하야"

    override val fullConditionConstraints = listOf(
        NotBeNeutral(),
        NotLord(),
    )

    override val minConditionConstraints = listOf(
        NotBeNeutral(),
        NotLord(),
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val nationName = nation?.name ?: "소속국"
        val generalName = general.name

        // Legacy: experience * (1 - 0.1 * betray), dedication * (1 - 0.1 * betray)
        val betrayCount = general.betray.toInt()
        val penaltyRate = 0.1 * betrayCount
        val newExp = floor(general.experience * (1.0 - penaltyRate)).toInt()
        val newDed = floor(general.dedication * (1.0 - penaltyRate)).toInt()
        val expLoss = general.experience.toInt() - newExp
        val dedLoss = general.dedication.toInt() - newDed
        val goldToNation = max(0, general.gold - DEFAULT_GOLD)
        val riceToNation = max(0, general.rice - DEFAULT_RICE)
        val newBetray = min(betrayCount + 1, MAX_BETRAY_COUNT)
        val isTroopLeader = general.troopId == general.id

        // General action log
        pushLog("<D><b>${nationName}</b></>에서 하야했습니다. <1>$date</>")
        // History log
        pushLog("_history:<D><b>${nationName}</b></>에서 하야")
        // Global action log
        pushLog("_global:<Y>${generalName}</>${josa(generalName, "이")} <D><b>${nationName}</b></>에서 <R>하야</>했습니다.")

        return CommandResult(
            success = true,
            logs = logs,
            message = buildString {
                append("""{"statChanges":{"experience":${-expLoss},"dedication":${-dedLoss}""")
                append(""","gold":${-goldToNation},"rice":${-riceToNation},"betray":${newBetray - betrayCount}}""")
                append(""","nationChanges":{"gold":$goldToNation,"rice":$riceToNation,"gennum":-1}""")
                append(""","leaveNation":true,"resetOfficer":true""")
                append(""","setPermission":"normal","setBelong":0,"setMakeLimit":12""")
                append(""","disbandTroop":$isTroopLeader""")
                append(""","inheritancePoint":{"key":"active_action","amount":1}""")
                append("}")
            }
        )
    }
}
