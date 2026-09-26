package opensamguk.logic.office

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.logic.content.ContentLedgerValidator

enum class OfficeJurisdiction { ZHOU, JUN, COUNTY }

data class OfficeSource(
    val book: String,
    val volume: String,
    val section: String,
    val quote: String,
)

/** A title in the source ledger. County titles are projections of the existing magistrate placement. */
data class OfficeDefinition(
    val id: String,
    val name: String,
    val jurisdiction: OfficeJurisdiction,
    val rankStones: String,
    val gameGrade: Int,
    val countyPlacementOnly: Boolean,
    val sources: List<OfficeSource>,
)

class OfficeCatalog private constructor(val definitions: List<OfficeDefinition>) {
    private val byId = definitions.associateBy { it.id }

    fun definition(id: String): OfficeDefinition? = byId[id]

    companion object {
        fun fromJson(raw: String): OfficeCatalog {
            ContentLedgerValidator.requireValid(raw)
            val root = Json.parseToJsonElement(raw).jsonObject
            require(root["countyOfficeAuthority"]?.jsonPrimitive?.content == "PROJECT_EXISTING_MAGISTRATE_PLACEMENT_ONLY") {
                "county office must remain a projection of magistrate placement"
            }
            val rows = root["rows"] as JsonArray
            return OfficeCatalog(rows.map { element ->
                val row = element as JsonObject
                val jurisdiction = OfficeJurisdiction.valueOf(row.string("jurisdictionUnit"))
                OfficeDefinition(
                    id = row.string("id"),
                    name = row.string("name"),
                    jurisdiction = jurisdiction,
                    rankStones = row.string("rankStones"),
                    gameGrade = row["values"]!!.jsonObject["gameGrade"]!!.jsonObject["value"]!!.jsonPrimitive.int,
                    countyPlacementOnly = jurisdiction == OfficeJurisdiction.COUNTY,
                    sources = (row["sources"] as JsonArray).map { source ->
                        val citation = source.jsonObject
                        OfficeSource(citation.string("book"), citation.string("volume"), citation.string("section"), citation.string("quote"))
                    },
                )
            })
        }

        fun loadClasspath(): OfficeCatalog {
            val raw = requireNotNull(OfficeCatalog::class.java.getResourceAsStream("/office/local-offices.json")) {
                "local office ledger missing"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
            return fromJson(raw)
        }

        private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    }
}
