package opensamguk.logic.misinformation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object MisinformationCatalog {
    fun parse(payload: String): MisinformationRules {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.getValue("schemaVersion").jsonPrimitive.int == 1)
        val rows = root.getValue("rows").jsonArray.map { it.jsonObject }
        require(rows.size == 1)
        val row = rows.single()
        require(row.text("id") == "misinformation:rules")
        require(row.text("grade") == "GAME_TERM" && row.text("displayBadge") == "게임 용어")
        require(row.getValue("sources").jsonArray.isEmpty())
        row.text("gameTermReason")
        val values = row.getValue("values").jsonObject
        return MisinformationRules(values.confirmed("durationTurns"), values.confirmed("detectionPermille"),
            values.confirmed("counterintelligenceMoneyCost"), values.confirmed("feignedCorpsMoneyCost"))
    }
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content.also {
    require(it.isNotBlank()) { "$key must not be blank" }
}

private fun JsonObject.confirmed(key: String): Int {
    val row = getValue(key).jsonObject
    require(row.text("status") == "CONFIRMED" && row.text("decidedBy") == "구현 에이전트")
    row.text("decidedAt")
    row.text("basis")
    return row.getValue("value").jsonPrimitive.int
}
