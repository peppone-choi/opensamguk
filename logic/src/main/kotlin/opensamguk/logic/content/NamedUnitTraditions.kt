package opensamguk.logic.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class UnitSourceCitation(
    val book: String,
    val volume: String,
    val section: String,
    val quote: String,
    val grade: String,
) {
    init {
        require(book.isNotBlank() && volume.isNotBlank() && section.isNotBlank() && quote.isNotBlank())
        require(grade == "PRIMARY" || grade == "ROMANCE" || grade == "SCHOLARLY")
    }
}

enum class UnitTraditionKind { NAMED, REGIONAL }

data class NamedUnitTradition(
    val header: CardHeader,
    val historicalName: String,
    val kind: UnitTraditionKind,
    val recruitmentSource: String,
    val attestedPeriod: String,
    val requiredGeneralName: String?,
    val requiredRegionName: String?,
    val requiredMapParentRegionId: String?,
    val bondRecruitmentKinds: Set<UnitBondKind>,
    val sources: List<UnitSourceCitation>,
) {
    init {
        require(header.kind == CardKind.UNIT && historicalName.isNotBlank())
        require(recruitmentSource.isNotBlank() && attestedPeriod.isNotBlank())
        require(requiredMapParentRegionId == null || (requiredRegionName != null && requiredMapParentRegionId.startsWith("PARENT-")))
        require(sources.isNotEmpty() && sources.any { historicalName in it.quote })
        require(header.provenance.size == sources.size)
    }
}

data class UnitFormationContext(
    /** Resolved by the caller against the current world, not by comparing display names. */
    val requiredGeneralPresent: Boolean,
    val requiredRegionPresent: Boolean,
    /** Card ids already issued anywhere in this server. */
    val issuedCardIds: Set<String>,
    val freeRenown: Int,
    /** Parent region ids currently eligible for recruitment in this server. */
    val eligibleMapParentRegionIds: Set<String> = emptySet(),
)

enum class UnitFormationFailure {
    REQUIRED_GENERAL_MISSING,
    REQUIRED_REGION_MISSING,
    UNIQUE_ALREADY_ISSUED,
    RENOWN_SHORTFALL,
}

object NamedUnitFormation {
    fun assess(unit: NamedUnitTradition, context: UnitFormationContext): UnitFormationFailure? {
        require(context.freeRenown >= 0)
        if (unit.requiredGeneralName != null && !context.requiredGeneralPresent) {
            return UnitFormationFailure.REQUIRED_GENERAL_MISSING
        }
        if (unit.requiredMapParentRegionId != null &&
            unit.requiredMapParentRegionId !in context.eligibleMapParentRegionIds) {
            return UnitFormationFailure.REQUIRED_REGION_MISSING
        }
        if (unit.requiredMapParentRegionId == null && unit.requiredRegionName != null && !context.requiredRegionPresent) {
            return UnitFormationFailure.REQUIRED_REGION_MISSING
        }
        if (unit.header.availability == CardAvailability.UNIQUE && unit.header.id in context.issuedCardIds) {
            return UnitFormationFailure.UNIQUE_ALREADY_ISSUED
        }
        if (unit.header.renownCost > context.freeRenown) {
            return UnitFormationFailure.RENOWN_SHORTFALL
        }
        return null
    }
}

/** Parses the curated S6 source ledger without importing the retired unit-set loader. */
object NamedUnitTraditions {
    fun parse(payload: String): List<NamedUnitTradition> {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.getValue("schemaVersion").jsonPrimitive.int == 1)
        val units = root.getValue("rows").jsonArray.map { node ->
            val row = node.jsonObject
            val grade = row.getValue("grade").jsonPrimitive.content
            require(grade in setOf("PRIMARY", "ROMANCE", "SCHOLARLY"))
            val historicalName = row.getValue("historicalName").jsonPrimitive.content
            val sources = row.getValue("sources").jsonArray.map { sourceNode ->
                val source = sourceNode.jsonObject
                UnitSourceCitation(
                    source.getValue("book").jsonPrimitive.content,
                    source.getValue("volume").jsonPrimitive.content,
                    source.getValue("section").jsonPrimitive.content,
                    source.getValue("quote").jsonPrimitive.content,
                    source.getValue("grade").jsonPrimitive.content,
                ).also { require(it.grade == grade) }
            }
            val decision = row.getValue("designDecision").jsonObject
            requireConfirmedDecision(decision)
            val cost = row.getValue("renownCost").jsonObject
            requireConfirmedDecision(cost)
            val requirements = row.getValue("requires").jsonObject
            val availability = CardAvailability.valueOf(row.getValue("availability").jsonPrimitive.content)
            val kind = UnitTraditionKind.valueOf(row.getValue("kind").jsonPrimitive.content)
            val header = CardHeader(
                id = row.getValue("id").jsonPrimitive.content,
                name = row.getValue("name").jsonPrimitive.content,
                kind = CardKind.UNIT,
                availability = availability,
                provenance = sources.map { CardProvenance.Citation(it.book, it.volume) },
                renownCost = cost.getValue("value").jsonPrimitive.int,
                costColors = row.getValue("costColors").jsonArray.map {
                    CostColor.valueOf(it.jsonPrimitive.content)
                }.toSet(),
                tags = setOf(kind.name),
            )
            require(header.id.startsWith("unit.") && header.renownCost > 0 && header.costColors.isNotEmpty())
            NamedUnitTradition(
                header = header,
                historicalName = historicalName,
                kind = kind,
                recruitmentSource = row.getValue("recruitmentSource").jsonPrimitive.content,
                attestedPeriod = row.getValue("attestedPeriod").jsonPrimitive.content,
                requiredGeneralName = optionalName(requirements, "generalName"),
                requiredRegionName = optionalName(requirements, "regionName"),
                requiredMapParentRegionId = requirements["mapParentRegionId"]?.let {
                    if (it == JsonNull) null else it.jsonPrimitive.content.also { id -> require(id.isNotBlank()) }
                },
                bondRecruitmentKinds = row.getValue("bondRecruitmentKinds").jsonArray.map {
                    UnitBondKind.valueOf(it.jsonPrimitive.content)
                }.toSet(),
                sources = sources,
            )
        }
        require(units.map { it.header.id }.distinct().size == units.size)
        return units.sortedBy { it.header.id }
    }

    private fun optionalName(row: JsonObject, key: String): String? = row.getValue(key).let {
        if (it == JsonNull) null else it.jsonPrimitive.content.also { name -> require(name.isNotBlank()) }
    }

    private fun requireConfirmedDecision(row: JsonObject) {
        require(row.getValue("status").jsonPrimitive.content == "CONFIRMED")
        require(row.getValue("decidedBy").jsonPrimitive.content == "구현 에이전트")
        require(row.getValue("decidedAt").jsonPrimitive.content.isNotBlank())
        require(row.getValue("basis").jsonPrimitive.content.isNotBlank())
    }
}
