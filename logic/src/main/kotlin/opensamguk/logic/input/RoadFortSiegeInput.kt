package opensamguk.logic.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object RoadFortSiegeInput {
    const val INPUT_ID = "action.siegeRoadFort"

    fun parse(argJson: String?): String? = try {
        val row = Json.parseToJsonElement(argJson ?: "").jsonObject
        if (row.keys != setOf("fortId")) null else row.getValue("fortId").jsonPrimitive.content
            .takeIf { it.length in 1..256 && it.matches(Regex("[A-Za-z0-9:,.@_-]+")) }
    } catch (_: IllegalArgumentException) { null }
}
