package opensamguk.logic.season

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Joins game-term event definitions with separately recorded, confirmed numeric decisions. */
object SeasonalCatalog {
    fun parseCalendar(valuesPayload: String): SeasonCalendar {
        val values = Json.parseToJsonElement(valuesPayload).jsonObject
        require(values.number("schemaVersion") == 1)
        val row = values.getValue("rows").jsonArray.map { it.jsonObject }
            .single { it.text("domain") == "calendar" }
        val decisions = row.getValue("values").jsonObject
        return SeasonCalendar(decisions.confirmed("springStartMonth"), decisions.confirmed("summerStartMonth"),
            decisions.confirmed("autumnStartMonth"), decisions.confirmed("winterStartMonth"))
    }

    fun parseRules(definitionsPayload: String, valuesPayload: String): List<SeasonalEventRule> {
        val definitions = Json.parseToJsonElement(definitionsPayload).jsonObject
        val values = Json.parseToJsonElement(valuesPayload).jsonObject
        require(definitions.number("schemaVersion") == 1 && values.number("schemaVersion") == 1)
        val numericRows = values.getValue("rows").jsonArray.map { it.jsonObject }
            .filter { it.text("domain") == "season" }.associateBy { it.text("kind") }
        require(numericRows.size == SeasonalEventKind.entries.size) { "missing or duplicate season numeric rule" }
        val rules = definitions.getValue("rows").jsonArray.map { it.jsonObject }.map { row ->
            require(row.text("grade") == "GAME_TERM" && row.text("displayBadge") == "게임 용어")
            require(row.getValue("sources").jsonArray.isEmpty())
            row.text("gameTermReason")
            val kind = SeasonalEventKind.valueOf(row.text("kind"))
            val numeric = checkNotNull(numericRows[kind.name]) { "missing numeric rule for $kind" }
                .getValue("values").jsonObject
            SeasonalEventRule(kind,
                row.getValue("seasons").jsonArray.map { Season.valueOf(it.jsonPrimitive.content) }.toSet(),
                row.getValue("terrains").jsonArray.map { Terrain.valueOf(it.jsonPrimitive.content) }.toSet(),
                numeric.confirmed("minimumPopulation"), numeric.confirmed("minimumAgriculture"),
                numeric.confirmed("chancePermille"),
                SeasonalEffect(numeric.confirmed("trust"), numeric.confirmed("population"),
                    numeric.confirmed("agriculture"), numeric.confirmed("displaced"),
                    numeric.confirmed("passageClosed").also { require(it in 0..1) } == 1))
        }
        require(rules.map { it.kind }.distinct().size == rules.size) { "duplicate season event definition" }
        require(rules.map { it.kind }.toSet() == SeasonalEventKind.entries.toSet()) { "missing season event definition" }
        return rules
    }
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content.also {
    require(it.isNotBlank()) { "$key must not be blank" }
}
private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int
private fun JsonObject.confirmed(key: String): Int {
    val row = getValue(key).jsonObject
    require(row.text("status") == "CONFIRMED")
    require(row.text("decidedBy") == "구현 에이전트")
    row.text("decidedAt")
    row.text("basis")
    return row.number("value")
}
