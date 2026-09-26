package opensamguk.logic.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Shared validation contract for layer 2/3 content ledgers. Historical quotes are checked against the corpus separately. */
object ContentLedgerValidator {
    private val grades = setOf("PRIMARY", "ROMANCE", "SCHOLARLY", "GAME_TERM")
    private val historical = grades - "GAME_TERM"
    private val sourceFields = listOf("book", "volume", "section", "quote")

    fun validate(raw: String): List<String> {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return listOf("schemaVersion: expected 1")
        if (root["schemaVersion"].primitiveInt() != 1) return listOf("schemaVersion: expected 1")
        val rows = root["rows"] as? JsonArray ?: return listOf("rows: expected array")
        val errors = mutableListOf<String>()
        val ids = mutableSetOf<String>()
        rows.forEachIndexed { index, element ->
            val path = "rows[$index]"
            val row = element as? JsonObject
            if (row == null) {
                errors += "$path: expected object"
                return@forEachIndexed
            }
            val id = row["id"].nonemptyString()
            if (id == null) errors += "$path.id: required"
            else if (!ids.add(id)) errors += "$path.id: duplicate $id"
            val grade = row["grade"].stringValue()
            if (grade !in grades) errors += "$path.grade: unknown $grade"
            val sources = row["sources"] as? JsonArray
            if (sources == null) errors += "$path.sources: expected array"
            if (grade in historical && sources.isNullOrEmpty()) errors += "$path.sources: historical row needs a citation"
            if (grade == "GAME_TERM") {
                if (row["gameTermReason"].nonemptyString() == null) errors += "$path.gameTermReason: required"
                if (row["displayBadge"].stringValue() != "게임 용어") errors += "$path.displayBadge: expected 게임 용어"
            }
            sources?.forEachIndexed { sourceIndex, sourceElement ->
                val sourcePath = "$path.sources[$sourceIndex]"
                val source = sourceElement as? JsonObject
                if (source == null) {
                    errors += "$sourcePath: expected object"
                    return@forEachIndexed
                }
                val sourceGrade = source["grade"].stringValue()
                if (sourceGrade !in grades) errors += "$sourcePath.grade: unknown $sourceGrade"
                else if (grade in historical && sourceGrade != grade) errors += "$sourcePath.grade: mixed historical grades"
                else if (grade == "GAME_TERM" && sourceGrade != "GAME_TERM") errors += "$sourcePath.grade: historical claim needs a separate row"
                sourceFields.forEach { field ->
                    if (source[field].nonemptyString() == null) errors += "$sourcePath.$field: required"
                }
            }
            val refs = row["refs"]
            if (refs != null && refs !is JsonArray) errors += "$path.refs: expected array"
            (refs as? JsonArray)?.forEach { if (it.nonemptyString() == null) errors += "$path.refs: expected nonempty id" }
            validateNumbers(row, path, errors)
        }
        rows.forEachIndexed { index, element ->
            val refs = (element as? JsonObject)?.get("refs") as? JsonArray ?: return@forEachIndexed
            refs.forEach { ref ->
                val id = ref.stringValue()
                if (id != null && id !in ids) errors += "rows[$index].refs: dangling $id"
            }
        }
        return errors
    }

    fun requireValid(raw: String) {
        val errors = validate(raw)
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }

    private fun validateNumbers(element: JsonElement, path: String, errors: MutableList<String>) {
        when (element) {
            is JsonObject -> {
                element["values"]?.let { values ->
                    if (values !is JsonObject) errors += "$path.values: expected object"
                    else values.forEach { (key, entry) ->
                        val value = (entry as? JsonObject)?.get("value") as? JsonPrimitive
                        if (value == null || value.isString || value.doubleOrNull == null) {
                            errors += "$path.values.$key: expected numeric decision object"
                        }
                    }
                }
                val value = element["value"] as? JsonPrimitive
                if (value != null && !value.isString && value.doubleOrNull != null) {
                    if (element["status"].stringValue() != "CONFIRMED") errors += "$path.status: expected CONFIRMED"
                    listOf("decidedBy", "decidedAt", "basis").forEach { field ->
                        if (element[field].nonemptyString() == null) errors += "$path.$field: required for numeric value"
                    }
                }
                element.forEach { (key, child) -> validateNumbers(child, "$path.$key", errors) }
            }
            is JsonArray -> element.forEachIndexed { index, child -> validateNumbers(child, "$path[$index]", errors) }
            else -> Unit
        }
    }

    private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.nonemptyString(): String? = stringValue()?.takeIf { it.isNotBlank() }
    private fun JsonElement?.primitiveInt(): Int? = (this as? JsonPrimitive)?.intOrNull
}
