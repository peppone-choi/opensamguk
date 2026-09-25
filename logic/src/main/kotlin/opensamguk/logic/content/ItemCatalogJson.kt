package opensamguk.logic.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class HwihaEquipmentDefinition(
    val id: String,
    val sourceCode: String,
    val name: String,
    val slot: TreasureSlot,
    val purchaseCost: Int,
    val requiredSecurity: Int,
    val consumable: Boolean,
) {
    init {
        require(id.isNotBlank() && sourceCode.isNotBlank() && name.isNotBlank())
        require(purchaseCost >= 0 && requiredSecurity >= 0)
    }
}

data class HwihaItemCatalog(
    val treasures: List<HwihaTreasureDefinition>,
    val equipment: List<HwihaEquipmentDefinition>,
) {
    init {
        require(treasures.map { it.header.id }.distinct().size == treasures.size)
        require(equipment.map { it.id }.distinct().size == equipment.size)
        require((treasures.map { it.sourceCode } + equipment.map { it.sourceCode }).distinct().size ==
            treasures.size + equipment.size)
    }
}

/** Strict reader for the two active ledgers; the excluded-row audit never enters gameplay. */
object HwihaItemCatalogJson {
    private const val TREASURE_RESOURCE = "hwiha/hwiha-treasure-cards-v1.json"
    private const val EQUIPMENT_RESOURCE = "hwiha/hwiha-equipment-v1.json"

    val CANON: HwihaItemCatalog by lazy {
        fun read(path: String) = checkNotNull(javaClass.classLoader.getResource(path)) {
            "missing HWIHA item catalogue resource: $path"
        }.readText()
        parse(read(TREASURE_RESOURCE), read(EQUIPMENT_RESOURCE))
    }

    fun parse(treasurePayload: String, equipmentPayload: String): HwihaItemCatalog {
        val treasureRoot = Json.parseToJsonElement(treasurePayload).jsonObject
        val equipmentRoot = Json.parseToJsonElement(equipmentPayload).jsonObject
        require(treasureRoot.getValue("schemaVersion").jsonPrimitive.int == 1)
        require(equipmentRoot.getValue("schemaVersion").jsonPrimitive.int == 1)
        require(treasureRoot.getValue("catalogId").jsonPrimitive.content == "hwiha-treasure-cards-v1")
        require(equipmentRoot.getValue("catalogId").jsonPrimitive.content == "hwiha-equipment-v1")
        require(treasureRoot.getValue("availabilityTwoCopyPolicy").jsonPrimitive.content == "PENDING")
        val treasures = treasureRoot.getValue("cards").jsonArray.map { node ->
            val row = node.jsonObject
            val header = row.getValue("header").jsonObject
            val legacy = row.getValue("legacy").jsonObject
            val sourceCode = row.getValue("sourceCode").jsonPrimitive.content
            val slot = TreasureSlot.valueOf(row.getValue("slot").jsonPrimitive.content)
            val availability = row.getValue("legacyAvailability").jsonPrimitive.int
            val copies = row.getValue("issuedCopies").let { if (it == JsonNull) null else it.jsonPrimitive.int }
            require(sourceCode == legacy.getValue("code").jsonPrimitive.content)
            require(slot == TreasureSlot.valueOf(legacy.getValue("registrySlot").jsonPrimitive.content.uppercase()))
            require(legacy.getValue("inRegistry").jsonPrimitive.boolean)
            require(availability == legacy.getValue("availability").jsonPrimitive.int)
            require((availability == 1 && copies == 1) || (availability == 2 && copies == null))
            require(header.getValue("name").jsonPrimitive.content == legacy.getValue("name").jsonPrimitive.content)
            val cardHeader = CardHeader(
                header.getValue("id").jsonPrimitive.content,
                header.getValue("name").jsonPrimitive.content,
                CardKind.valueOf(header.getValue("kind").jsonPrimitive.content),
                CardAvailability.valueOf(header.getValue("availability").jsonPrimitive.content),
                header.getValue("provenance").jsonArray.map { provenance(it.jsonObject) },
                header.getValue("renownCost").jsonPrimitive.int,
                header.getValue("costColors").jsonArray.map { CostColor.valueOf(it.jsonPrimitive.content) }.toSet(),
                header.getValue("tags").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            )
            require(cardHeader.id == "treasure:$sourceCode")
            HwihaTreasureDefinition(cardHeader, sourceCode, slot, copies,
                legacy.getValue("cost").jsonPrimitive.int, row.getValue("sourceRowIndex").jsonPrimitive.int)
        }
        val equipment = equipmentRoot.getValue("equipment").jsonArray.map { node ->
            val row = node.jsonObject
            val legacy = row.getValue("legacy").jsonObject
            val sourceCode = row.getValue("sourceCode").jsonPrimitive.content
            val slot = TreasureSlot.valueOf(row.getValue("slot").jsonPrimitive.content)
            require(row.getValue("id").jsonPrimitive.content == "equipment:$sourceCode")
            require(sourceCode == legacy.getValue("code").jsonPrimitive.content)
            require(slot == TreasureSlot.valueOf(legacy.getValue("registrySlot").jsonPrimitive.content.uppercase()))
            require(legacy.getValue("availability").jsonPrimitive.int == 0)
            require(legacy.getValue("inRegistry").jsonPrimitive.boolean && legacy.getValue("buyable").jsonPrimitive.boolean)
            require(row.getValue("supply").jsonPrimitive.content == "UNLIMITED")
            HwihaEquipmentDefinition(row.getValue("id").jsonPrimitive.content, sourceCode,
                row.getValue("name").jsonPrimitive.content, slot,
                legacy.getValue("cost").jsonPrimitive.int, legacy.getValue("reqSecu").jsonPrimitive.int,
                legacy.getValue("consumable").jsonPrimitive.boolean)
        }
        return HwihaItemCatalog(treasures, equipment)
    }

    private fun provenance(row: JsonObject): CardProvenance = when (row.getValue("kind").jsonPrimitive.content) {
        "GAME_TERM" -> CardProvenance.GameTerm
        "CITATION" -> CardProvenance.Citation(row.getValue("book").jsonPrimitive.content,
            row.getValue("volume").jsonPrimitive.content)
        else -> throw IllegalArgumentException("unknown treasure provenance")
    }
}
