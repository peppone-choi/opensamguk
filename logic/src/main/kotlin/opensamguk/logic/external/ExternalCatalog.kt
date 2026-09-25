package opensamguk.logic.external

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ExternalSource(val book: String, val volume: String, val section: String, val quote: String)
data class ExternalActor(
    val id: ExternalActorId,
    val label: String,
    val source: ExternalSource,
    val attestationDate: String?,
    val subjectPeriod: IntRange?,
    val activation: String,
)

/** Historical names are candidates until a dated, scenario-specific actor assignment is proved. */
object ExternalCatalog {
    fun parseActors(payload: String): List<ExternalActor> {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.number("schemaVersion") == 1)
        val actors = root.array("rows").map { raw ->
            val row = raw.jsonObject
            require(row.string("grade") == "PRIMARY")
            require(row.string("organizationType") == "HISTORICAL_NETWORK")
            val sources = row.array("sources")
            require(sources.size == 1) { "external actor needs one source per historical claim" }
            val source = sources.single().jsonObject
            require(source.string("grade") == "PRIMARY")
            val evidence = ExternalSource(source.string("book"), source.string("volume"),
                source.string("section"), source.string("quote"))
            require(listOf(evidence.book, evidence.volume, evidence.section, evidence.quote).all { it.isNotBlank() })
            val period = row.getValue("subjectPeriod").jsonObject
            val from = period["fromYear"]?.jsonPrimitive?.content?.toIntOrNull()
            val to = period["toYear"]?.jsonPrimitive?.content?.toIntOrNull()
            require((from == null) == (to == null)) { "partial subject period" }
            if (from != null) require(from <= to!!)
            val activation = row.string("activation")
            require(activation == "CANDIDATE" || (activation == "ACTIVE" && from != null)) {
                "undated external actor cannot be active"
            }
            ExternalActor(ExternalActorId(row.string("id")), row.string("label"), evidence,
                row["attestationDate"]?.jsonPrimitive?.content?.takeUnless { it == "null" },
                if (from == null) null else from..to!!, activation)
        }
        require(actors.map { it.id }.distinct().size == actors.size) { "duplicate actor id" }
        return actors
    }

    fun parseEventRules(payload: String): List<ExternalEventRule> {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.number("schemaVersion") == 1)
        val ledgerRows = root.array("rows").map { it.jsonObject }
        val vocabulary = ledgerRows.single { it.string("domain") == "relation-vocabulary" }
            .array("relations").map { ExternalRelation.valueOf(it.jsonPrimitive.content) }
        require(vocabulary.toSet() == ExternalRelation.entries.toSet() && vocabulary.distinct().size == vocabulary.size) {
            "external relation vocabulary mismatch"
        }
        val rows = ledgerRows.filter { it.string("domain") == "external" }
        val rules = rows.map { row ->
            val values = row.getValue("values").jsonObject
            ExternalEventRule(ExternalEventKind.valueOf(row.string("kind")),
                row.array("relations").map { ExternalRelation.valueOf(it.jsonPrimitive.content) }.toSet(),
                ExternalRelation.valueOf(row.string("resultingRelation")),
                values.confirmed("chancePermille"),
                ExternalEffect(values.confirmed("money"), values.confirmed("grain"), values.confirmed("trust"),
                    values.confirmed("population"), values.confirmed("defence")))
        }
        require(rules.map { it.kind }.distinct().size == rules.size) { "duplicate external event rule" }
        require(rules.map { it.kind }.toSet() == ExternalEventKind.entries.toSet()) { "missing external event rule" }
        return rules
    }
}

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content.also {
    require(it.isNotBlank()) { "$key must not be blank" }
}
internal fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int
internal fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
internal fun JsonObject.confirmed(key: String): Int {
    val row = getValue(key).jsonObject
    require(row.string("status") == "CONFIRMED")
    require(row.string("decidedBy") == "구현 에이전트")
    row.string("decidedAt")
    row.string("basis")
    return row.number("value")
}
