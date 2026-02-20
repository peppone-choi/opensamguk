package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.entity.General
import com.opensam.service.ItemService
import kotlin.random.Random

class 장비매매(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "장비매매"

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()

        val itemType = arg?.get("itemType") as? String ?: return CommandResult(
            success = false,
            logs = listOf("인자가 없습니다."),
        )
        val itemCode = arg?.get("itemCode") as? String ?: return CommandResult(
            success = false,
            logs = listOf("인자가 없습니다."),
        )

        val buying = itemCode != "None"

        if (buying) {
            return buyItem(itemType, itemCode, date)
        } else {
            return sellItem(itemType, date)
        }
    }

    private fun buyItem(itemType: String, itemCode: String, date: String): CommandResult {
        val result = ItemService.buyAndEquipItem(general, itemCode, itemType)
        if (!result.success) {
            return CommandResult(success = false, logs = listOf(result.message))
        }

        pushLog("${result.message} <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
        )
    }

    private fun sellItem(itemType: String, date: String): CommandResult {
        val result = ItemService.sellItemByType(general, itemType)
        if (!result.success) {
            return CommandResult(success = false, logs = listOf(result.message))
        }

        pushLog("${result.message} <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
        )
    }
}
