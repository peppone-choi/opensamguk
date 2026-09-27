package opensamguk.logic.imperial

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class CentralOfficeClass { THREE_DUKE, NINE_MINISTER, SECRETARIAT, GENERAL_COMMISSION }

data class CentralOfficeDefinition(
    val id: String,
    val name: String,
    val officeClass: CentralOfficeClass,
    /** The original source label, including UNKNOWN where no rank was verified. */
    val rankLabel: String,
)

class CentralOfficeCatalog private constructor(val definitions: List<CentralOfficeDefinition>) {
    val ids: Set<String> = definitions.mapTo(linkedSetOf()) { it.id }

    fun definition(id: String): CentralOfficeDefinition? = definitions.firstOrNull { it.id == id }

    companion object {
        fun decode(source: String): CentralOfficeCatalog {
            val root = Json.parseToJsonElement(source).jsonObject
            require(root.getValue("schemaVersion").jsonPrimitive.int == 1)
            val definitions = root.getValue("rows").jsonArray.map { element ->
                val row = element.jsonObject
                require(row.getValue("kind").jsonPrimitive.content == "CENTRAL_OFFICE")
                require(row.getValue("grade").jsonPrimitive.content == "PRIMARY")
                val definition = CentralOfficeDefinition(
                    id = row.getValue("id").jsonPrimitive.content,
                    name = row.getValue("name").jsonPrimitive.content,
                    officeClass = CentralOfficeClass.valueOf(row.getValue("officeClass").jsonPrimitive.content),
                    rankLabel = row.getValue("rankLabel").jsonPrimitive.content,
                )
                require(definition.id.startsWith("office.") && definition.name.isNotBlank() && definition.rankLabel.isNotBlank())
                definition
            }
            require(definitions.isNotEmpty())
            require(definitions.map { it.id }.toSet().size == definitions.size)
            return CentralOfficeCatalog(definitions)
        }
    }
}
