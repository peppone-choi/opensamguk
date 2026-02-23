package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.service.ItemService
import kotlin.random.Random

private val ITEM_TYPE_NAMES = mapOf(
    "horse" to "명마",
    "weapon" to "무기",
    "book" to "서적",
    "item" to "도구",
)

class 장비매매(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "장비매매"

    private val itemType: String? get() = arg?.get("itemType") as? String
    private val itemCode: String? get() = arg?.get("itemCode") as? String

    override val minConditionConstraints: List<Constraint>
        get() = listOf(
            ReqCityTrader(),
        )

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            val constraints = mutableListOf<Constraint>(
                ReqCityTrader(),
            )
            val code = itemCode
            if (code != null && code != "None") {
                val item = ItemService.getItemInfo(code)
                if (item != null) {
                    constraints.add(ReqCityCapacity("secu", "치안 수치", item.reqSecu))
                }
                constraints.add(ReqGeneralGold(cost.gold))
            }
            return constraints
        }

    override fun getCost(): CommandCost {
        val code = itemCode ?: return CommandCost()
        if (code == "None") return CommandCost()
        val item = ItemService.getItemInfo(code) ?: return CommandCost()
        return CommandCost(gold = item.cost)
    }

    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()

        val iType = itemType ?: return CommandResult(
            success = false,
            logs = listOf("인자가 없습니다."),
        )
        val iCode = itemCode ?: return CommandResult(
            success = false,
            logs = listOf("인자가 없습니다."),
        )

        val buying = iCode != "None"

        if (buying) {
            return buyItem(iType, iCode, date)
        } else {
            return sellItem(iType, date)
        }
    }

    private fun buyItem(itemType: String, itemCode: String, date: String): CommandResult {
        val item = ItemService.getItemInfo(itemCode)
            ?: return CommandResult(success = false, logs = listOf("아이템 정보가 없습니다."))

        val itemName = item.name
        val itemRawName = item.rawName ?: itemName
        val cost = item.cost

        pushLog("<C>${itemName}</> 구입했습니다. <1>$date</>")

        val exp = 10

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":${-cost},"experience":$exp},"itemChanges":{"type":"$itemType","code":"$itemCode","action":"buy"}}"""
        )
    }

    private fun sellItem(itemType: String, date: String): CommandResult {
        val currentItemCode = general.getItemCode(itemType)
        if (currentItemCode == null || currentItemCode == "None") {
            return CommandResult(success = false, logs = listOf("판매할 아이템이 없습니다."))
        }

        val item = ItemService.getItemInfo(currentItemCode)
            ?: return CommandResult(success = false, logs = listOf("아이템 정보가 없습니다."))

        val itemName = item.name
        val itemRawName = item.rawName ?: itemName
        val sellPrice = item.cost / 2

        pushLog("<C>${itemName}</> 판매했습니다. <1>$date</>")

        // If selling a rare (non-buyable) item, push global logs
        val globalLogs = mutableListOf<String>()
        if (!item.buyable) {
            val generalName = general.name
            globalLogs.add("<Y>${generalName}</> <C>${itemName}</> 판매했습니다!")
            globalLogs.add("<R><b>【판매】</b></> <Y>${generalName}</> <C>${itemName}</> 판매했습니다!")
        }

        val exp = 10

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":$sellPrice,"experience":$exp},"itemChanges":{"type":"$itemType","code":"None","action":"sell"},"globalLogs":${globalLogs.joinToString(",", "[", "]") { "\"$it\"" }}}"""
        )
    }
}
