package com.opensam.dto

data class ItemEquipRequest(
    val itemCode: String,
    val itemType: String? = null,
)

data class ItemUnequipRequest(
    val itemType: String,
)

data class ItemUseRequest(
    val itemType: String? = null,
    val itemCode: String? = null,
)

data class ItemDiscardRequest(
    val itemType: String,
)

data class ItemGiveRequest(
    val itemType: String,
    val targetGeneralId: Long,
)
