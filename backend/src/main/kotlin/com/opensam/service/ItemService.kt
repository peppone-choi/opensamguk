package com.opensam.service

import com.opensam.engine.modifier.ConsumableItem
import com.opensam.engine.modifier.ItemModifiers
import com.opensam.entity.General
import com.opensam.repository.GeneralRepository
import org.springframework.stereotype.Service

@Service
class ItemService(
    private val generalRepository: GeneralRepository,
) {
    fun buyItem(general: General, itemCode: String): Boolean {
        val meta = ItemModifiers.getMeta(itemCode) ?: return false
        if (!meta.buyable) return false
        if (general.gold < meta.cost) return false

        val slot = slotForCategory(meta.category) ?: return false
        val currentCode = getSlotValue(general, slot)
        if (currentCode != "None") return false

        general.gold -= meta.cost
        setSlotValue(general, slot, itemCode)

        if (meta.consumable) {
            val item = ItemModifiers.get(itemCode) as? ConsumableItem
            if (item != null) {
                general.meta = general.meta.toMutableMap().apply {
                    put("item_uses_$itemCode", 0)
                }
            }
        }

        generalRepository.save(general)
        return true
    }

    fun sellItem(general: General, slot: String): Boolean {
        val itemCode = getSlotValue(general, slot)
        if (itemCode == "None") return false

        val meta = ItemModifiers.getMeta(itemCode) ?: return false
        val sellPrice = meta.cost / 2

        general.gold += sellPrice
        setSlotValue(general, slot, "None")

        general.meta = general.meta.toMutableMap().apply {
            remove("item_uses_$itemCode")
        }

        generalRepository.save(general)
        return true
    }

    fun consumeItem(general: General, itemCode: String): Boolean {
        val item = ItemModifiers.get(itemCode) as? ConsumableItem ?: return false
        val usesKey = "item_uses_$itemCode"
        val currentUses = (general.meta[usesKey] as? Number)?.toInt() ?: 0
        if (currentUses >= item.maxUses) return false

        when (item.effect) {
            "preTurnHeal" -> general.injury = (general.injury - 10).coerceIn(0, 80).toShort()
            "battleAtmos" -> general.atmos = (general.atmos + item.value).coerceIn(0, 150).toShort()
            "battleTrain" -> general.train = (general.train + item.value).coerceIn(0, 110).toShort()
        }

        general.meta = general.meta.toMutableMap().apply { put(usesKey, currentUses + 1) }
        generalRepository.save(general)
        return true
    }

    private fun slotForCategory(category: String): String? = when (category) {
        "weapon" -> "weaponCode"
        "book" -> "bookCode"
        "horse" -> "horseCode"
        "misc" -> "itemCode"
        else -> null
    }

    private fun getSlotValue(general: General, slot: String): String = when (slot) {
        "weaponCode" -> general.weaponCode
        "bookCode" -> general.bookCode
        "horseCode" -> general.horseCode
        "itemCode" -> general.itemCode
        else -> "None"
    }

    private fun setSlotValue(general: General, slot: String, value: String) {
        when (slot) {
            "weaponCode" -> general.weaponCode = value
            "bookCode" -> general.bookCode = value
            "horseCode" -> general.horseCode = value
            "itemCode" -> general.itemCode = value
        }
    }
}
