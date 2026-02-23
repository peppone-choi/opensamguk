package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
import kotlin.random.Random

private const val PRE_REQ_TURN = 2
private const val DEFAULT_GLOBAL_DELAY: Short = 9
private const val TRAIN_CAP: Short = 100
private const val ATMOS_CAP: Short = 100

class che_필사즉생(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "필사즉생"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = PRE_REQ_TURN
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")

        val expDed = 5 * (PRE_REQ_TURN + 1)
        general.experience += expDed
        general.dedication += expDed

        // Set strategic command limit
        n.strategicCmdLimit = DEFAULT_GLOBAL_DELAY.toShort()

        // Raise train/atmos to 100 for all nation generals (including self)
        val nationGenerals = services?.generalRepository?.findByNationId(n.id) ?: emptyList()
        for (gen in nationGenerals) {
            var changed = false
            if (gen.train < TRAIN_CAP) {
                gen.train = TRAIN_CAP
                changed = true
            }
            if (gen.atmos < ATMOS_CAP) {
                gen.atmos = ATMOS_CAP
                changed = true
            }
            if (changed && gen.id != general.id) {
                services?.generalRepository?.save(gen)
            }
        }

        // Apply to self as well
        if (general.train < TRAIN_CAP) general.train = TRAIN_CAP
        if (general.atmos < ATMOS_CAP) general.atmos = ATMOS_CAP

        pushLog("$actionName 발동! <1>$date</>")
        return CommandResult(true, logs)
    }
}
