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
    fun buyItem(generalId: Long, itemCode: String, requestedItemType: String? = null): ItemActionResult {
        val general = generalRepository.findById(generalId).orElse(null)
            ?: return ItemActionResult(false, "장수를 찾을 수 없습니다.")
        val result = buyAndEquipItem(general, itemCode, requestedItemType)
        if (result.success) {
            generalRepository.save(general)
        }
        return result
    }

    fun sellItem(generalId: Long, itemType: String): ItemActionResult {
        val general = generalRepository.findById(generalId).orElse(null)
            ?: return ItemActionResult(false, "장수를 찾을 수 없습니다.")
        val result = sellItemByType(general, itemType)
        if (result.success) {
            generalRepository.save(general)
        }
        return result
    }

    fun consumeItem(generalId: Long, itemType: String? = null, itemCode: String? = null): ItemActionResult {
        val general = generalRepository.findById(generalId).orElse(null)
            ?: return ItemActionResult(false, "장수를 찾을 수 없습니다.")
        val result = useItem(general, itemType, itemCode)
        if (result.success) {
            generalRepository.save(general)
        }
        return result
    }

    fun unequipItem(generalId: Long, itemType: String): ItemActionResult {
        val general = generalRepository.findById(generalId).orElse(null)
            ?: return ItemActionResult(false, "장수를 찾을 수 없습니다.")
        val result = unequipItem(general, itemType)
        if (result.success) {
            generalRepository.save(general)
        }
        return result
    }

    fun discardItem(generalId: Long, itemType: String): ItemActionResult {
        val general = generalRepository.findById(generalId).orElse(null)
            ?: return ItemActionResult(false, "장수를 찾을 수 없습니다.")
        val result = discardItem(general, itemType)
        if (result.success) {
            generalRepository.save(general)
        }
        return result
    }

    fun giveItem(fromGeneralId: Long, targetGeneralId: Long, itemType: String): ItemActionResult {
        val fromGeneral = generalRepository.findById(fromGeneralId).orElse(null)
            ?: return ItemActionResult(false, "장수를 찾을 수 없습니다.")
        val targetGeneral = generalRepository.findById(targetGeneralId).orElse(null)
            ?: return ItemActionResult(false, "대상 장수를 찾을 수 없습니다.")
        val result = giveItem(fromGeneral, targetGeneral, itemType)
        if (result.success) {
            generalRepository.save(fromGeneral)
            generalRepository.save(targetGeneral)
        }
        return result
    }

    companion object {
        data class ItemSlot(
            val itemType: String,
            val category: String,
            val field: String,
            val displayName: String,
        )

        private val slots = listOf(
            ItemSlot(itemType = "weapon", category = "weapon", field = "weaponCode", displayName = "무기"),
            ItemSlot(itemType = "book", category = "book", field = "bookCode", displayName = "서적"),
            ItemSlot(itemType = "horse", category = "horse", field = "horseCode", displayName = "군마"),
            ItemSlot(itemType = "item", category = "misc", field = "itemCode", displayName = "도구"),
        )

        private fun slotByItemType(itemType: String): ItemSlot? = slots.find { it.itemType == itemType }

        private fun slotByCategory(category: String): ItemSlot? = slots.find { it.category == category }

        private fun getSlotValue(general: General, slot: ItemSlot): String = when (slot.field) {
            "weaponCode" -> general.weaponCode
            "bookCode" -> general.bookCode
            "horseCode" -> general.horseCode
            "itemCode" -> general.itemCode
            else -> "None"
        }

        private fun setSlotValue(general: General, slot: ItemSlot, value: String) {
            when (slot.field) {
                "weaponCode" -> general.weaponCode = value
                "bookCode" -> general.bookCode = value
                "horseCode" -> general.horseCode = value
                "itemCode" -> general.itemCode = value
            }
        }

        private fun clearConsumableMeta(general: General, itemCode: String) {
            general.meta = general.meta.toMutableMap().apply {
                remove("item_uses_$itemCode")
            }
        }

        private fun initializeConsumableMeta(general: General, itemCode: String) {
            general.meta = general.meta.toMutableMap().apply {
                put("item_uses_$itemCode", 0)
            }
        }

        private fun applyConsumableEffect(general: General, item: ConsumableItem): Boolean {
            when (item.effect) {
                "preTurnHeal" -> {
                    general.injury = (general.injury - item.value).coerceIn(0, 80).toShort()
                    return true
                }

                "battleAtmos" -> {
                    general.atmos = (general.atmos + item.value).coerceIn(0, 150).toShort()
                    return true
                }

                "battleTrain" -> {
                    general.train = (general.train + item.value).coerceIn(0, 110).toShort()
                    return true
                }

                "sabotageSuccess" -> {
                    val key = "item_effect_sabotage_success"
                    val current = (general.meta[key] as? Number)?.toInt() ?: 0
                    general.meta = general.meta.toMutableMap().apply {
                        put(key, current + item.value)
                    }
                    return true
                }

                "battleSnipe" -> {
                    val key = "item_effect_battle_snipe"
                    val current = (general.meta[key] as? Number)?.toInt() ?: 0
                    general.meta = general.meta.toMutableMap().apply {
                        put(key, current + item.value)
                    }
                    return true
                }

                else -> return false
            }
        }

        fun buyAndEquipItem(general: General, itemCode: String, requestedItemType: String? = null): ItemActionResult {
            val meta = ItemModifiers.getMeta(itemCode)
                ?: return ItemActionResult(false, "존재하지 않는 아이템입니다.")
            if (!meta.buyable) {
                return ItemActionResult(false, "구매할 수 없는 아이템입니다.")
            }
            if (general.gold < meta.cost) {
                return ItemActionResult(false, "금이 부족합니다. (필요: ${meta.cost})")
            }

            val slot = slotByCategory(meta.category)
                ?: return ItemActionResult(false, "잘못된 아이템 종류입니다.")
            if (requestedItemType != null && slot.itemType != requestedItemType) {
                return ItemActionResult(false, "선택한 장비 종류와 아이템이 일치하지 않습니다.")
            }
            if (getSlotValue(general, slot) != "None") {
                return ItemActionResult(false, "이미 ${slot.displayName}를 장착 중입니다.")
            }

            general.gold -= meta.cost
            setSlotValue(general, slot, itemCode)
            if (meta.consumable) {
                initializeConsumableMeta(general, itemCode)
            }

            return ItemActionResult(true, "${meta.rawName}을(를) 금 ${meta.cost}에 구입했습니다.")
        }

        fun sellItemByType(general: General, itemType: String): ItemActionResult {
            val slot = slotByItemType(itemType)
                ?: return ItemActionResult(false, "잘못된 아이템 종류입니다.")
            val itemCode = getSlotValue(general, slot)
            if (itemCode == "None") {
                return ItemActionResult(false, "판매할 아이템이 없습니다.")
            }

            val meta = ItemModifiers.getMeta(itemCode)
            val sellPrice = ((meta?.cost ?: 0) / 2).coerceAtLeast(0)
            general.gold += sellPrice
            setSlotValue(general, slot, "None")
            clearConsumableMeta(general, itemCode)

            val itemName = meta?.rawName ?: itemCode
            return ItemActionResult(true, "${itemName}을(를) 금 ${sellPrice}에 판매했습니다.")
        }

        fun equipItem(general: General, itemCode: String, requestedItemType: String? = null): ItemActionResult {
            val meta = ItemModifiers.getMeta(itemCode)
                ?: return ItemActionResult(false, "존재하지 않는 아이템입니다.")
            val slot = slotByCategory(meta.category)
                ?: return ItemActionResult(false, "장착할 수 없는 아이템입니다.")
            if (requestedItemType != null && slot.itemType != requestedItemType) {
                return ItemActionResult(false, "선택한 장비 종류와 아이템이 일치하지 않습니다.")
            }
            if (getSlotValue(general, slot) != "None") {
                return ItemActionResult(false, "이미 ${slot.displayName}를 장착 중입니다.")
            }

            setSlotValue(general, slot, itemCode)
            if (meta.consumable) {
                initializeConsumableMeta(general, itemCode)
            }
            return ItemActionResult(true, "${meta.rawName}을(를) 장착했습니다.")
        }

        fun unequipItem(general: General, itemType: String): ItemActionResult {
            val slot = slotByItemType(itemType)
                ?: return ItemActionResult(false, "잘못된 아이템 종류입니다.")
            val itemCode = getSlotValue(general, slot)
            if (itemCode == "None") {
                return ItemActionResult(false, "해제할 아이템이 없습니다.")
            }

            val itemName = ItemModifiers.getMeta(itemCode)?.rawName ?: itemCode
            setSlotValue(general, slot, "None")
            clearConsumableMeta(general, itemCode)
            return ItemActionResult(true, "$itemName 장착을 해제했습니다.")
        }

        fun useItem(general: General, itemType: String? = null, itemCode: String? = null): ItemActionResult {
            val slot = when {
                itemType != null -> slotByItemType(itemType)
                itemCode != null -> slotByCategory(ItemModifiers.getMeta(itemCode)?.category ?: "")
                else -> slotByItemType("item")
            } ?: return ItemActionResult(false, "잘못된 아이템 종류입니다.")

            val equippedCode = getSlotValue(general, slot)
            if (equippedCode == "None") {
                return ItemActionResult(false, "사용할 아이템이 없습니다.")
            }
            if (itemCode != null && equippedCode != itemCode) {
                return ItemActionResult(false, "장착 중인 아이템과 요청한 아이템이 다릅니다.")
            }

            val item = ItemModifiers.get(equippedCode) as? ConsumableItem
                ?: return ItemActionResult(false, "사용할 수 없는 아이템입니다.")
            val usesKey = "item_uses_$equippedCode"
            val currentUses = (general.meta[usesKey] as? Number)?.toInt() ?: 0
            if (currentUses >= item.maxUses) {
                return ItemActionResult(false, "사용 횟수를 모두 소진했습니다.")
            }

            val didApply = applyConsumableEffect(general, item)
            if (!didApply) {
                return ItemActionResult(false, "정의되지 않은 아이템 효과입니다.")
            }

            val nextUses = currentUses + 1
            general.meta = general.meta.toMutableMap().apply {
                put(usesKey, nextUses)
            }

            if (nextUses >= item.maxUses) {
                setSlotValue(general, slot, "None")
                clearConsumableMeta(general, equippedCode)
                return ItemActionResult(true, "아이템 효과를 사용하고 소모했습니다.")
            }
            return ItemActionResult(true, "아이템 효과를 사용했습니다.")
        }

        fun discardItem(general: General, itemType: String): ItemActionResult {
            val slot = slotByItemType(itemType)
                ?: return ItemActionResult(false, "잘못된 아이템 종류입니다.")
            val itemCode = getSlotValue(general, slot)
            if (itemCode == "None") {
                return ItemActionResult(false, "버릴 아이템이 없습니다.")
            }

            val itemName = ItemModifiers.getMeta(itemCode)?.rawName ?: itemCode
            setSlotValue(general, slot, "None")
            clearConsumableMeta(general, itemCode)
            return ItemActionResult(true, "${itemName}을(를) 버렸습니다.")
        }

        fun giveItem(fromGeneral: General, targetGeneral: General, itemType: String): ItemActionResult {
            val slot = slotByItemType(itemType)
                ?: return ItemActionResult(false, "잘못된 아이템 종류입니다.")

            val sourceItemCode = getSlotValue(fromGeneral, slot)
            if (sourceItemCode == "None") {
                return ItemActionResult(false, "증여할 아이템이 없습니다.")
            }
            if (getSlotValue(targetGeneral, slot) != "None") {
                return ItemActionResult(false, "대상 장수가 이미 해당 종류의 아이템을 장착 중입니다.")
            }

            setSlotValue(fromGeneral, slot, "None")
            setSlotValue(targetGeneral, slot, sourceItemCode)

            val sourceUsesKey = "item_uses_$sourceItemCode"
            val sourceUses = (fromGeneral.meta[sourceUsesKey] as? Number)?.toInt()
            clearConsumableMeta(fromGeneral, sourceItemCode)
            if (sourceUses != null) {
                targetGeneral.meta = targetGeneral.meta.toMutableMap().apply {
                    put(sourceUsesKey, sourceUses)
                }
            }

            val itemName = ItemModifiers.getMeta(sourceItemCode)?.rawName ?: sourceItemCode
            return ItemActionResult(true, "${itemName}을(를) ${targetGeneral.name}에게 증여했습니다.")
        }
    }
}

data class ItemActionResult(
    val success: Boolean,
    val message: String,
)
