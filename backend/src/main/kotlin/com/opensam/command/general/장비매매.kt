package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.engine.modifier.ItemModifiers
import com.opensam.entity.General
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
        val meta = ItemModifiers.getMeta(itemCode) ?: return CommandResult(
            success = false,
            logs = listOf("존재하지 않는 아이템입니다."),
        )
        if (!meta.buyable) return CommandResult(
            success = false,
            logs = listOf("구매할 수 없는 아이템입니다."),
        )
        if (general.gold < meta.cost) return CommandResult(
            success = false,
            logs = listOf("금이 부족합니다. (필요: ${meta.cost})"),
        )

        val slot = slotForCategory(meta.category) ?: return CommandResult(
            success = false,
            logs = listOf("잘못된 아이템 종류입니다."),
        )
        val currentCode = getSlotValue(slot)
        if (currentCode != "None") return CommandResult(
            success = false,
            logs = listOf("이미 장착 중인 아이템이 있습니다. 먼저 판매하세요."),
        )

        general.gold -= meta.cost
        setSlotValue(slot, itemCode)

        pushLog("<C>${meta.rawName}</>을(를) <S>금 ${meta.cost}</>에 구입했습니다. <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
        )
    }

    private fun sellItem(itemType: String, date: String): CommandResult {
        val slot = when (itemType) {
            "horse" -> "horseCode"
            "weapon" -> "weaponCode"
            "book" -> "bookCode"
            "item" -> "itemCode"
            else -> return CommandResult(success = false, logs = listOf("잘못된 아이템 종류입니다."))
        }
        val currentCode = getSlotValue(slot)
        if (currentCode == "None") return CommandResult(
            success = false,
            logs = listOf("판매할 아이템이 없습니다."),
        )

        val meta = ItemModifiers.getMeta(currentCode)
        val sellPrice = (meta?.cost ?: 0) / 2

        general.gold += sellPrice
        setSlotValue(slot, "None")

        pushLog("<C>${meta?.rawName ?: currentCode}</>을(를) <S>금 $sellPrice</>에 판매했습니다. <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
        )
    }

    private fun slotForCategory(category: String): String? = when (category) {
        "weapon" -> "weaponCode"
        "book" -> "bookCode"
        "horse" -> "horseCode"
        "misc" -> "itemCode"
        else -> null
    }

    private fun getSlotValue(slot: String): String = when (slot) {
        "weaponCode" -> general.weaponCode
        "bookCode" -> general.bookCode
        "horseCode" -> general.horseCode
        "itemCode" -> general.itemCode
        else -> "None"
    }

    private fun setSlotValue(slot: String, value: String) {
        when (slot) {
            "weaponCode" -> general.weaponCode = value
            "bookCode" -> general.bookCode = value
            "horseCode" -> general.horseCode = value
            "itemCode" -> general.itemCode = value
        }
    }
}
