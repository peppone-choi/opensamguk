package opensamguk.logic.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class PresetEvidenceGrade { PRIMARY, ROMANCE, SCHOLARLY, GAME_TERM }

data class PresetCitation(
    val book: String,
    val volume: String,
    val section: String,
    val quote: String,
    val grade: PresetEvidenceGrade,
) {
    init {
        require(book.isNotBlank() && volume.isNotBlank() && section.isNotBlank() && quote.isNotBlank())
        require(grade != PresetEvidenceGrade.GAME_TERM)
    }
}

data class IdentityPresetSource(
    val id: String,
    val name: String,
    val grade: PresetEvidenceGrade,
    val sources: List<PresetCitation>,
    val gameTermReason: String?,
    val historicalClaimScope: String?,
) {
    init {
        require(id.startsWith("identity.") && name.isNotBlank())
        if (grade == PresetEvidenceGrade.GAME_TERM) {
            require(sources.isEmpty() && !gameTermReason.isNullOrBlank())
            require(historicalClaimScope == null)
        } else {
            require(sources.isNotEmpty() && sources.all { it.grade == grade })
            require(!historicalClaimScope.isNullOrBlank())
            require(gameTermReason == null)
        }
    }

    val badges: List<String>
        get() = if (grade == PresetEvidenceGrade.GAME_TERM) listOf("게임 용어")
        else sources.map { "${it.book} ${it.volume}" }.distinct()
}

/** Source badges only; gameplay identity state and transitions are S6-8/S6-9 integration work. */
object IdentityPresetSources {
    private val expectedIds = setOf(
        "identity.virtue", "identity.dao", "identity.bandit", "identity.names", "identity.mohist",
        "identity.legalist", "identity.military", "identity.buddhist", "identity.wudoumi",
        "identity.confucian", "identity.yinyang", "identity.diplomatist", "identity.taiping",
        "identity.neutral", "identity.none",
    )

    fun parse(payload: String): List<IdentityPresetSource> {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.getValue("schemaVersion").jsonPrimitive.int == 1)
        require(root.getValue("defaultWhenSourcesConflict").jsonPrimitive.content == "ROMANCE")
        val rows = root.getValue("rows").jsonArray.map { node ->
            val row = node.jsonObject
            val grade = PresetEvidenceGrade.valueOf(row.getValue("grade").jsonPrimitive.content)
            val sources = row.getValue("sources").jsonArray.map { sourceNode ->
                val source = sourceNode.jsonObject
                PresetCitation(
                    source.getValue("book").jsonPrimitive.content,
                    source.getValue("volume").jsonPrimitive.content,
                    source.getValue("section").jsonPrimitive.content,
                    source.getValue("quote").jsonPrimitive.content,
                    PresetEvidenceGrade.valueOf(source.getValue("grade").jsonPrimitive.content),
                )
            }
            requireConfirmedDecision(row.getValue("designDecision").jsonObject)
            if (grade == PresetEvidenceGrade.GAME_TERM) {
                require(row.getValue("displayBadge").jsonPrimitive.content == "게임 용어")
            }
            IdentityPresetSource(
                id = row.getValue("id").jsonPrimitive.content,
                name = row.getValue("name").jsonPrimitive.content,
                grade = grade,
                sources = sources,
                gameTermReason = row["gameTermReason"]?.jsonPrimitive?.content,
                historicalClaimScope = row["historicalClaimScope"]?.jsonPrimitive?.content,
            )
        }
        require(rows.map { it.id }.distinct().size == rows.size)
        require(rows.map { it.id }.toSet() == expectedIds)
        return rows.sortedBy { it.id }
    }

    private fun requireConfirmedDecision(row: JsonObject) {
        require(row.getValue("status").jsonPrimitive.content == "CONFIRMED")
        require(row.getValue("decidedBy").jsonPrimitive.content == "구현 에이전트")
        require(row.getValue("decidedAt").jsonPrimitive.content.isNotBlank())
        require(row.getValue("basis").jsonPrimitive.content.isNotBlank())
    }
}
