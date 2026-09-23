package opensamguk.logic.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import opensamguk.logic.economy.HwihaResources

/** Where FULL vision comes from (spec §7 plus the 2026-09-23 user decision). */
enum class VisionSourceKind { SELF, OWN_CORPS, RETINUE, TERRITORY, SCOUT_POST, WATCHTOWER_BEACON }

data class TroopBand(val code: String, val label: String, val minInclusive: Int)

/**
 * Confirmed vision numbers (2026-09-23 user decision). The only source is `data/curated/han/hwiha-vision-rules-v1.json`; this object
 * copies nothing into code and fails closed when the file is missing or malformed.
 */
object HwihaVisionRules {
    const val ADJACENCY_RULE = "SHARED_BORDER_4_NEIGHBOUR"
    private const val RESOURCE = "hwiha/hwiha-vision-rules-v1.json"

    class Rules internal constructor(
        val sourceRadius: Map<VisionSourceKind, Int>,
        val troopBands: List<TroopBand>,
        val scoutInputId: String,
        val scoutCost: HwihaResources,
    ) {
        init {
            require(sourceRadius.keys == VisionSourceKind.entries.toSet()) { "Every vision source needs a radius" }
            require(sourceRadius.values.all { it in 0..8 }) { "Vision radius must be within 0..8" }
            require(troopBands.isNotEmpty() && troopBands.first().minInclusive == 0) { "Troop bands must start at zero" }
            require(troopBands.zipWithNext().all { (a, b) -> a.minInclusive < b.minInclusive }) { "Troop bands must ascend" }
            require(troopBands.map { it.code }.distinct().size == troopBands.size) { "Duplicate troop band code" }
            require(troopBands.all { it.code.isNotBlank() && it.label.isNotBlank() })
            require(scoutInputId == HwihaScoutInput.INPUT_ID) { "Scout rules bind a different input" }
            // No debit path exists yet (warehouse access + revision binding); a non-zero cost would be a fake cost.
            require(scoutCost == HwihaResources()) { "Scout cost must stay zero until a debit path exists" }
        }

        fun radius(kind: VisionSourceKind): Int = sourceRadius.getValue(kind)

        /** Troops are never negative; the last band whose lower bound is reached. */
        fun band(troops: Int): TroopBand {
            require(troops >= 0) { "Troops must not be negative" }
            return troopBands.last { troops >= it.minInclusive }
        }

        fun bandByCode(code: String): TroopBand? = troopBands.firstOrNull { it.code == code }
    }

    val CANON: Rules by lazy {
        parse(checkNotNull(HwihaVisionRules::class.java.classLoader.getResource(RESOURCE)) {
            "hwiha vision rules resource is missing: $RESOURCE"
        }.readText())
    }

    fun parse(payload: String): Rules {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.getValue("schemaVersion").jsonPrimitive.int == 1) { "unsupported vision rules schemaVersion" }
        require(root.getValue("ledgerId").jsonPrimitive.content == "hwiha-vision-rules-v1") { "unexpected ledgerId" }
        require(root.getValue("unit").jsonPrimitive.content == "COMMANDERY") { "vision unit must be COMMANDERY" }
        require(root.getValue("adjacency").jsonObject.getValue("rule").jsonPrimitive.content == ADJACENCY_RULE) {
            "vision adjacency rule differs from the implemented shared-border rule"
        }
        val radius = root.getValue("sourceRadius").jsonObject
        require(radius.keys == VisionSourceKind.entries.map { it.name }.toSet()) { "unexpected vision sources: ${radius.keys}" }
        val bands = root.getValue("troopBands").jsonArray.map {
            val row = it.jsonObject
            require(row.keys == setOf("code", "label", "minInclusive")) { "unexpected troop band fields" }
            TroopBand(row.getValue("code").jsonPrimitive.content, row.getValue("label").jsonPrimitive.content,
                row.getValue("minInclusive").jsonPrimitive.int)
        }
        val scout = root.getValue("scout").jsonObject
        require(scout.getValue("target").jsonPrimitive.content == "ADJACENT_COMMANDERY") { "scout target rule changed" }
        val cost: JsonObject = scout.getValue("cost").jsonObject
        require(cost.keys == setOf("money", "grain", "iron", "timber", "horses")) { "scout cost must list the five resources" }
        return Rules(
            sourceRadius = VisionSourceKind.entries.associateWith { radius.getValue(it.name).jsonPrimitive.int },
            troopBands = bands,
            scoutInputId = scout.getValue("inputId").jsonPrimitive.content,
            scoutCost = HwihaResources(cost.getValue("money").jsonPrimitive.long, cost.getValue("grain").jsonPrimitive.long,
                cost.getValue("iron").jsonPrimitive.long, cost.getValue("timber").jsonPrimitive.long,
                cost.getValue("horses").jsonPrimitive.long),
        )
    }
}
