package opensamguk.logic.imperial

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads the curated game-rule ledger; no credibility weights are compiled into the model. */
object EdictCredibilityRulesCodec {
    fun decode(source: String): EdictCredibilityRules {
        val root = Json.parseToJsonElement(source).jsonObject
        require(root.getValue("schemaVersion").jsonPrimitive.int == 1)
        val weights = root.getValue("rows").jsonArray.associate { element ->
            val row = element.jsonObject
            require(row.getValue("grade").jsonPrimitive.content == "GAME_TERM")
            require(row.getValue("displayBadge").jsonPrimitive.content == "게임 용어")
            val factor = EdictCredibilityFactor.valueOf(row.getValue("factor").jsonPrimitive.content)
            require(row.getValue("id").jsonPrimitive.content == "edict.credibility.$factor")
            val value = row.getValue("weight").jsonObject
            require(value.getValue("status").jsonPrimitive.content == "CONFIRMED")
            require(value.getValue("decidedBy").jsonPrimitive.content == "구현 에이전트")
            require(value.getValue("basis").jsonPrimitive.content.isNotBlank())
            factor to value.getValue("value").jsonPrimitive.int
        }
        require(weights.size == root.getValue("rows").jsonArray.size)
        return EdictCredibilityRules(weights)
    }
}
